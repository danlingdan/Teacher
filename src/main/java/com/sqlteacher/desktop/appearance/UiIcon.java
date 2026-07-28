package com.sqlteacher.desktop.appearance;

/** Small audited SVG path set used by the desktop shell. Paths are based on Material Symbols shapes. */
public enum UiIcon {
    HOME("M3 13h8V3H3v10zm2-2V5h4v6H5zm8 10h8V11h-8v10zm2-2v-6h4v6h-4zM3 21h8v-6H3v6zm2-2v-2h4v2H5zm10-10h6V3h-6v6zm2-2V5h2v2h-2z"),
    CODE("M8 17 3 12l5-5 1.4 1.4L5.8 12l3.6 3.6L8 17zm8 0-1.4-1.4 3.6-3.6-3.6-3.6L16 7l5 5-5 5zm-5.3 4L9 20.4 13.3 3l1.7.6L10.7 21z"),
    PRACTICE("M6 2h9l5 5v15H6V2zm8 2v4h4l-4-4zm-5 9h8v-2H9v2zm0 4h8v-2H9v2z"),
    LIBRARY("M4 4h5v16H4V4zm2 2v12h1V6H6zm5-2h5v16h-5V4zm2 2v12h1V6h-1zm5-1 2-.5 3.5 14-2 .5L18 5z"),
    CHART("M4 19h16v2H2V3h2v16zm3-2V9h3v8H7zm5 0V5h3v12h-3zm5 0v-6h3v6h-3z"),
    BOOK("M4 3h7a3 3 0 0 1 3 3v13a3 3 0 0 0-3-3H4V3zm16 0h-4a4.9 4.9 0 0 0-2 .4A5 5 0 0 1 16 7v9h4V3z"),
    SPARK("M12 2l1.5 5.5L19 9l-5.5 1.5L12 16l-1.5-5.5L5 9l5.5-1.5L12 2zm7 13 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15z"),
    TABLE("M3 4h18v16H3V4zm2 2v3h14V6H5zm0 5v3h4v-3H5zm6 0v3h8v-3h-8zm-6 5v2h4v-2H5zm6 0v2h8v-2h-8z"),
    CLOUD("M7 19a5 5 0 0 1-.8-9.9A7 7 0 0 1 19.7 11 4 4 0 0 1 19 19H7z"),
    SETTINGS("M19.4 13a7.7 7.7 0 0 0 .1-1 7.7 7.7 0 0 0-.1-1l2.1-1.6-2-3.4-2.5 1a8 8 0 0 0-1.7-1L15 3.3h-4L10.6 6a8 8 0 0 0-1.7 1L6.4 6 4.5 9.4 6.6 11a7.7 7.7 0 0 0-.1 1 7.7 7.7 0 0 0 .1 1l-2.1 1.6L6.4 18l2.5-1a8 8 0 0 0 1.7 1l.4 2.7h4l.4-2.7a8 8 0 0 0 1.7-1l2.5 1 2-3.4L19.4 13zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"),
    USER("M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5 0-9 2.5-9 5.5V22h18v-2.5C21 16.5 17 14 12 14z"),
    REFRESH("M17.7 6.3A8 8 0 1 0 20 12h-2a6 6 0 1 1-1.8-4.3L13 11h8V3l-3.3 3.3z"),
    DATABASE("M12 2C7 2 3 3.8 3 6v12c0 2.2 4 4 9 4s9-1.8 9-4V6c0-2.2-4-4-9-4zm0 2c4.4 0 7 1.3 7 2s-2.6 2-7 2-7-1.3-7-2 2.6-2 7-2zm0 16c-4.4 0-7-1.3-7-2v-2.1c1.7.8 4.2 1.1 7 1.1s5.3-.3 7-1.1V18c0 .7-2.6 2-7 2z"),
    COLUMN("M4 3h16v18H4V3zm2 2v14h3V5H6zm5 0v14h3V5h-3zm5 0v14h2V5h-2z"),
    KEY("M14 3a7 7 0 0 0-6.7 9H2v4h3v3h4v-3h2.3A7 7 0 1 0 14 3zm0 4a3 3 0 1 1 0 6 3 3 0 0 1 0-6z"),
    WARNING("M12 2 1 21h22L12 2zm0 6 1 7h-2l1-7zm0 10a1.2 1.2 0 1 1 0-2.4A1.2 1.2 0 0 1 12 18z");

    private final String path;

    UiIcon(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
