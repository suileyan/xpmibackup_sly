package com.suileyan.cloud;

/**
 * 远端文件条目，用于AIDL层构造小米DFS的SmbFile对象
 * 由 CloudFileHelp.RemoteEntry 迁移而来
 */
public class RemoteEntry {

    public final String name;
    public final long size;
    public final boolean directory;
    public final long modifiedTime;

    public RemoteEntry(String name, long size, boolean directory, long modifiedTime) {
        this.name = name;
        this.size = size;
        this.directory = directory;
        this.modifiedTime = modifiedTime;
    }
}
