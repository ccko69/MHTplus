package com.ccko.mhtplus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class LibraryActivity extends AppCompatActivity {

	private RecyclerView rvLibrary;
	private TextView tvEmpty;
	private SwipeRefreshLayout swipeRefresh;
	private LibraryAdapter adapter;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_library);

		rvLibrary = findViewById(R.id.rv_library);
		tvEmpty = findViewById(R.id.tv_empty);
		swipeRefresh = findViewById(R.id.swipe_refresh);

		adapter = new LibraryAdapter(new LibraryAdapter.Callback() {
			@Override
			public void onOpen(LibraryEntry entry) {
				openSavedMht(entry);
			}

			@Override
			public void onDelete(LibraryEntry entry) {
				deleteEntry(entry);
			}
		});

		rvLibrary.setLayoutManager(new LinearLayoutManager(this));
		rvLibrary.setAdapter(adapter);

		swipeRefresh.setOnRefreshListener(this::reloadList);

		reloadList();
	}

	private void reloadList() {
		swipeRefresh.setRefreshing(true);
		List<LibraryEntry> list = scanSavedFolders();
		adapter.setItems(list);
		tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
		swipeRefresh.setRefreshing(false);
	}

	private List<LibraryEntry> scanSavedFolders() {
		List<LibraryEntry> out = new ArrayList<>();
		File baseDir = getExternalFilesDir("mht");
		if (baseDir == null)
			baseDir = new File(getFilesDir(), "mht");
		if (!baseDir.exists())
			return out;

		File[] folders = baseDir.listFiles(File::isDirectory);
		if (folders == null)
			return out;

		for (File f : folders) {
			try {
				File meta = new File(f, "metadata.json");
				String title = "";
				String url = "";
				long ts = f.lastModified();
				String savedPath = null;
				if (meta.exists()) {
					FileInputStream fis = new FileInputStream(meta);
					InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
					StringBuilder sb = new StringBuilder();
					char[] buf = new char[1024];
					int r;
					while ((r = isr.read(buf)) > 0)
						sb.append(buf, 0, r);
					isr.close();
					JSONObject jo = new JSONObject(sb.toString());
					title = jo.optString("title", "");
					url = jo.optString("url", "");
					ts = jo.optLong("timestamp", ts);
					savedPath = jo.optString("savedPath", null);
				} else {
					// Fallback: try to find page.mht
					File page = new File(f, "page.mht");
					if (page.exists())
						savedPath = page.getAbsolutePath();
				}
				if (savedPath == null)
					continue;
				out.add(new LibraryEntry(f.getName(), title, url, ts, savedPath));
			} catch (Exception ignored) {
			}
		}

		// simple: newest first
		out.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
		return out;
	}

	private void openSavedMht(LibraryEntry entry) {
		try {
			File file = new File(entry.savedPath);
			if (!file.exists()) {
				Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show();
				reloadList();
				return;
			}
			// Open in BrowserActivity (read-only hint via Intent extra)
			Intent i = new Intent(this, BrowserActivity.class);
			i.putExtra("open_mht_path", entry.savedPath);
			i.putExtra("read_only", true);
			startActivity(i);
		} catch (Exception e) {
			Toast.makeText(this, "Unable to open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

	private void deleteEntry(LibraryEntry entry) {
		File baseDir = getExternalFilesDir("mht");
		if (baseDir == null)
			baseDir = new File(getFilesDir(), "mht");
		File dir = new File(baseDir, entry.folderName);
		boolean ok = deleteRecursively(dir);
		if (ok) {
			Toast.makeText(this, "Deleted: " + entry.folderName, Toast.LENGTH_SHORT).show();
			reloadList();
		} else {
			Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
		}
	}

	private boolean deleteRecursively(File f) {
		if (f == null || !f.exists())
			return true;
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File c : children) {
					if (!deleteRecursively(c))
						return false;
				}
			}
		}
		return f.delete();
	}
}
