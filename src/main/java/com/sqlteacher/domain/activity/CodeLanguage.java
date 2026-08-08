package com.sqlteacher.domain.activity;

public enum CodeLanguage {
    JAVA("Main.java"),
    PYTHON("main.py"),
    C("main.c"),
    CPP("main.cpp");

    private final String sourceFileName;

    CodeLanguage(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String sourceFileName() {
        return sourceFileName;
    }
}
