package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record ClassAssignment(String id,String classroomId,String exerciseId,String title,Instant createdAt) {
    public ClassAssignment {
        if(id==null||id.isBlank()||classroomId==null||classroomId.isBlank()||exerciseId==null||exerciseId.isBlank()||title==null||title.isBlank())throw new IllegalArgumentException("Assignment fields must not be blank");
        if(createdAt==null)throw new IllegalArgumentException("createdAt must not be null");
    }
}
