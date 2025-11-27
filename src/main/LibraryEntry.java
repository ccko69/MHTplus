package com.ccko.mhtplus;

public class LibraryEntry {
	public final String folderName;
	public final String title;
	public final String url;
	public final long timestamp;
	public final String savedPath;

	public LibraryEntry(String folderName, String title, String url, long timestamp, String savedPath) {
		this.folderName = folderName;
		this.title = title;
		this.url = url;
		this.timestamp = timestamp;
		this.savedPath = savedPath;
	}
}