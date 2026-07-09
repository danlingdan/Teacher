package com.sqlteacher.application.metadata;

import java.util.List;

public interface DatabaseMetadataService {
    List<DatabaseTable> listTables(String connectionId);
}
