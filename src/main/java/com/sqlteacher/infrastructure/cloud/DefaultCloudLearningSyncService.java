package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.*;
import com.sqlteacher.application.event.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Idempotent event synchronization with a stable per-installation device id. */
public final class DefaultCloudLearningSyncService implements CloudLearningSyncService {
    private static final String CLOUD_ID = "_cloud_event_id";
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final LearningEventQueryService query;
    private final LearningEventRecorder recorder;
    private final Path stateDirectory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public DefaultCloudLearningSyncService(CloudApiClient api, CloudSessionService sessions,
            LearningEventQueryService query, LearningEventRecorder recorder, Path stateDirectory) {
        this.api=Objects.requireNonNull(api); this.sessions=Objects.requireNonNull(sessions);
        this.query=Objects.requireNonNull(query); this.recorder=Objects.requireNonNull(recorder);
        this.stateDirectory=Objects.requireNonNull(stateDirectory);
    }

    @Override public SyncResult synchronize() {
        var session=sessions.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
        String userId=session.user().id();
        String deviceId=readOrCreate("cloud-device-id.txt", UUID.randomUUID().toString());
        List<CloudSyncItem> pending=new ArrayList<>();
        for (LearningEventType type:LearningEventType.values()) for(var event:query.queryEventsByType(type,null,null)) {
            if(event.attributes().containsKey(CLOUD_ID)) continue;
            if(!userId.equals(event.attributes().get(LearningEventOwnerProvider.OWNER_ATTRIBUTE))) continue;
            try {
                String payload=json.writeValueAsString(Map.of("connectionId",event.connectionId(),"successful",event.successful(),"attributes",event.attributes()));
                pending.add(new CloudSyncItem(deviceId+":"+event.id(),type.name(),payload,event.occurredAt(),0));
            } catch(IOException error){throw new IllegalStateException("无法编码待同步学习记录",error);}
        }
        int uploaded=0;
        for(int start=0;start<pending.size();start+=500) uploaded+=api.uploadSyncItems(session.accessToken(),pending.subList(start,Math.min(start+500,pending.size())));
        String userStateKey=userId.replaceAll("[^A-Za-z0-9_-]","_");
        String cursorFile="cloud-sync-cursor-"+userStateKey+".txt";
        long cursor=Long.parseLong(readOrCreate(cursorFile,"0"));
        int downloaded=0;
        while(true){List<CloudSyncItem> items=api.downloadSyncItems(session.accessToken(),cursor);if(items.isEmpty())break;
            for(CloudSyncItem item:items){cursor=Math.max(cursor,item.version());if(item.id().startsWith(deviceId+":"))continue;importItem(item,userId);downloaded++;}
            if(items.size()<500)break;
        }
        write(cursorFile,Long.toString(cursor));
        return new SyncResult(uploaded,downloaded,cursor);
    }

    private void importItem(CloudSyncItem item,String userId){try{Map<String,Object> payload=json.readValue(item.payloadJson(),new TypeReference<>(){});Map<String,String> attributes=new LinkedHashMap<>();Object raw=payload.get("attributes");if(raw instanceof Map<?,?> map)map.forEach((k,v)->attributes.put(String.valueOf(k),String.valueOf(v)));attributes.put(CLOUD_ID,item.id());attributes.put(LearningEventOwnerProvider.OWNER_ATTRIBUTE,userId);recorder.record(new LearningEvent(LearningEventType.valueOf(item.type()),item.occurredAt(),String.valueOf(payload.getOrDefault("connectionId","cloud")),Boolean.parseBoolean(String.valueOf(payload.getOrDefault("successful",false))),attributes));}catch(IOException|IllegalArgumentException error){throw new IllegalStateException("云端学习记录格式无效",error);}}
    private String readOrCreate(String name,String fallback){try{Files.createDirectories(stateDirectory);Path file=stateDirectory.resolve(name);if(Files.notExists(file))Files.writeString(file,fallback);return Files.readString(file).trim();}catch(IOException error){throw new IllegalStateException("无法读写同步状态",error);}}
    private void write(String name,String value){try{Files.createDirectories(stateDirectory);Files.writeString(stateDirectory.resolve(name),value);}catch(IOException error){throw new IllegalStateException("无法保存同步状态",error);}}
}
