package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.ai.OpenAiCompatibleConfiguration;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

public final class InMemoryNetworkAiSettingsService implements NetworkAiSettingsService {
    private OpenAiCompatibleConfiguration configuration;
    @Override public synchronized void configure(URI endpoint,String model,char[] apiKey){clear();configuration=new OpenAiCompatibleConfiguration(endpoint,model,apiKey);Arrays.fill(apiKey,'\0');}
    @Override public synchronized Optional<OpenAiCompatibleConfiguration> current(){return Optional.ofNullable(configuration);}
    @Override public synchronized void clear(){if(configuration!=null){configuration.destroy();configuration=null;}}
}
