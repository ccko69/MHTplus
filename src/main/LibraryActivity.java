package com.ccko.mhtplus;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryActivity extends AppCompatActivity {

	private RecyclerView rvLibrary;
	private TextView tvEmpty;
	private SwipeRefreshLayout swipeRefresh;
	private LibraryAdapter adapter;

	private Menu menu; // reference to toolbar menu
	private Toolbar toolbar;
	private final Set<String> pendingDeleteFolders = new HashSet<>(); // temporary helper

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_library);
		handleIncomingIntent(getIntent());

		toolbar = findViewById(R.id.library_toolbar);
		if (toolbar != null) {
			setSupportActionBar(toolbar);

			// ensure toolbar menu is visible and its clicks are handled
			toolbar.inflateMenu(R.menu.library_menu);
			toolbar.setOnMenuItemClickListener(item -> LibraryActivity.this.onOptionsItemSelected(item));
		}

		// ensure toolbar menu is visible and its clicks are handled
		toolbar.inflateMenu(R.menu.library_menu);
		toolbar.setOnMenuItemClickListener(item -> {
			// forward to Activity's onOptionsItemSelected for unified handling
			return LibraryActivity.this.onOptionsItemSelected(item);
		});

		rvLibrary = findViewById(R.id.rv_library);
		tvEmpty = findViewById(R.id.tv_empty);
		swipeRefresh = findViewById(R.id.swipe_refresh);

		adapter = new LibraryAdapter(new LibraryAdapter.Callback() {
			@Override
			public void onOpen(LibraryEntry entry) {
				openSavedMht(entry);
			}

			//	@Override
			//	public void onDelete(LibraryEntry entry) {
			// single-item delete from adapter callback (kept for compatibility)
			//		confirmAndDeleteEntries(java.util.Collections.singletonList(entry));
			//	}

			@Override
			public void onSelectionChanged(int count) {
				updateToolbarForSelection(count);
			}

			@Override
			public void onLongPress(LibraryEntry entry) {
				// Enter selection mode if not already
				if (!adapter.isSelectionMode()) {
					adapter.enterSelectionMode(true);
				}

				// Toggle selection for the pressed item (select if not selected)
				adapter.toggleSelection(entry);

				// Update toolbar UI (enables delete/rename etc.) and show overflow for discoverability
				updateToolbarForSelection(adapter.getSelectedItems().size());
				if (adapter.getSelectedItems().size() > 0 && toolbar != null) {
					// show overflow so user sees available actions when selection starts
					toolbar.showOverflowMenu();
				}
			}
		});

		rvLibrary.setLayoutManager(new LinearLayoutManager(this));
		rvLibrary.setAdapter(adapter);

		swipeRefresh.setOnRefreshListener(this::reloadList);

		reloadList();
	}

	private void handleIncomingIntent(Intent intent) {
		if (intent == null)
			return;
		try {
			// Prefer explicit data URI (content:// or file://)
			android.net.Uri data = intent.getData();
			if (data != null) {
				Intent i = new Intent(this, BrowserActivity.class);
				i.setData(data);
				i.putExtra("read_only", true);
				startActivity(i);
				return;
			}

			// Fallback: extras named "open_mht_path"
			String path = intent.getStringExtra("open_mht_path");
			if (path != null && !path.isEmpty()) {
				Intent i = new Intent(this, BrowserActivity.class);
				i.putExtra("open_mht_path", path);
				i.putExtra("read_only", true);
				startActivity(i);
				return;
			}

			// ACTION_SEND with stream
			String action = intent.getAction();
			if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_VIEW.equals(action)) {
				android.net.Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
				if (stream == null)
					stream = intent.getData();
				if (stream != null) {
					Intent i = new Intent(this, BrowserActivity.class);
					i.setData(stream);
					i.putExtra("read_only", true);
					startActivity(i);
					return;
				}
			}
		} catch (Exception ignored) {
		}
	}

	private void reloadList() {
		swipeRefresh.setRefreshing(true);
		List<LibraryEntry> list = scanSavedFolders();
		adapter.setItems(list);
		tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
		swipeRefresh.setRefreshing(false);
		updateToolbarForSelection(adapter.getSelectedItems().size());
	}

	// 3. scanSavedFolders
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
				// Accept both meta.json and metadata.json
				File meta = new File(f, "meta.json");
				if (!meta.exists())
					meta = new File(f, "metadata.json");

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
					try {
						isr.close();
					} catch (Exception ignored) {
					}
					try {
						fis.close();
					} catch (Exception ignored) {
					}

					JSONObject jo = new JSONObject(sb.toString());
					url = jo.optString("url", jo.optString("uri", ""));
					ts = jo.optLong("timestamp", ts);
					// Accept both "savedPath" and "path"
					savedPath = jo.optString("savedPath", jo.optString("path", null));
				} else {
					// Fallback: try to find page.mht
					File page = new File(f, "page.mht");
					if (page.exists())
						savedPath = page.getAbsolutePath();
				}

				if (savedPath == null)
					continue;

				// Derive display title as filename if possible, otherwise fallback to folder name
				String displayTitle;
				if (savedPath.startsWith("content://")) {
					// content URI may not expose filename reliably; use folder name
					displayTitle = f.getName();
				} else {
					File sp = new File(savedPath);
					displayTitle = sp.getName();
					if (displayTitle == null || displayTitle.isEmpty())
						displayTitle = f.getName();
				}

				out.add(new LibraryEntry(f.getName(), displayTitle, url, ts, savedPath));
			} catch (Exception ignored) {
			}
		}

		// Sort newest first
		out.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
		return out;
	}

	private void openSavedMht(LibraryEntry entry) {
		try {
			String path = entry.savedPath;
			if (path == null) {
				Toast.makeText(this, "Missing path", Toast.LENGTH_SHORT).show();
				return;
			}
			Intent i = new Intent(this, BrowserActivity.class);
			i.putExtra("read_only", true);

			if (path.startsWith("content://")) {
				// Send as data URI
				i.setData(Uri.parse(path));
			} else {
				File file = new File(path);
				if (!file.exists()) {
					Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show();
					reloadList();
					return;
				}
				i.putExtra("open_mht_path", path);
			}
			startActivity(i);
		} catch (Exception e) {
			Toast.makeText(this, "Unable to open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

	private void deleteEntry(LibraryEntry entry) {
		confirmAndDeleteEntries(java.util.Collections.singletonList(entry));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.library_menu, menu);
		this.menu = menu;
		updateToolbarForSelection(0); // initial state
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_select_all) {
			// enter selection mode and select all
			adapter.enterSelectionMode(true);
			for (LibraryEntry e : adapter.getItems()) {
				adapter.selectById(e.folderName, true);
			}
			updateToolbarForSelection(adapter.getSelectedItems().size());
			return true;
		} else if (id == R.id.action_delete) {
			List<LibraryEntry> sel = adapter.getSelectedItems();
			if (!sel.isEmpty())
				confirmAndDeleteEntries(sel);
			return true;
		} else if (id == R.id.action_rename) {
			List<LibraryEntry> sel = adapter.getSelectedItems();
			if (sel.size() == 1)
				showRenameDialog(sel.get(0));
			return true;
		} else if (id == R.id.sort_name || id == R.id.sort_time_desc || id == R.id.sort_time_asc) {
			applySort(id);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void updateToolbarForSelection(int count) {
		if (menu == null)
			return;
		MenuItem delete = menu.findItem(R.id.action_delete);
		MenuItem rename = menu.findItem(R.id.action_rename);

		if (delete != null)
			delete.setEnabled(count > 0);
		if (rename != null)
			rename.setEnabled(count == 1);

		if (getSupportActionBar() != null) {
			if (count > 0)
				getSupportActionBar().setTitle(count + " selected");
			else
				getSupportActionBar().setTitle("Library");
		}
	}

	private static final String PREF_SORT_KEY = "library_sort";

	private void applySort(int menuId) {
		// Determine sort mode and persist it
		String sortMode;
		if (menuId == R.id.sort_name) {
			sortMode = "name_asc";
		} else if (menuId == R.id.sort_time_asc) {
			sortMode = "time_asc";
		} else { // default / R.id.sort_time_desc
			sortMode = "time_desc";
		}

		// persist choice (simple SharedPreferences)
		getSharedPreferences("mhtplus_prefs", MODE_PRIVATE).edit().putString(PREF_SORT_KEY, sortMode).apply();

		// Apply to current adapter items
		List<LibraryEntry> items = adapter.getItems();
		if (items == null || items.isEmpty()) {
			// nothing to sort
			return;
		}

		if ("name_asc".equals(sortMode)) {
			items.sort((a, b) -> {
				String n1 = (a.title != null && !a.title.isEmpty()) ? a.title : a.folderName;
				String n2 = (b.title != null && !b.title.isEmpty()) ? b.title : b.folderName;
				return n1.compareToIgnoreCase(n2);
			});
		} else if ("time_asc".equals(sortMode)) {
			items.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
		} else { // time_desc
			items.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
		}

		// update adapter and UI
		adapter.setItems(items);
		adapter.notifyDataSetChanged();
	}

	private void confirmAndDeleteEntries(List<LibraryEntry> entries) {
		int count = entries.size();
		new AlertDialog.Builder(this).setTitle("Delete")
				.setMessage("Delete " + count + " item" + (count > 1 ? "s" : "")
						+ " from library? Originals will not be removed.")
				.setPositiveButton("Delete", (dialog, which) -> deleteEntries(entries))
				.setNegativeButton("Cancel", null).show();
	}

	private void deleteEntries(List<LibraryEntry> entries) {
		File baseDir = getExternalFilesDir("mht");
		if (baseDir == null)
			baseDir = new File(getFilesDir(), "mht");

		boolean anyFailed = false;
		for (LibraryEntry e : entries) {
			try {
				File dir = new File(baseDir, e.folderName);
				// If folder exists under our app directory, safely delete it
				if (dir.exists() && dir.isDirectory()) {
					deleteRecursively(dir);
				} else {
					// Folder not present: try to remove meta.json if exists in a folder with that name
					File metaDir = new File(baseDir, e.folderName);
					File meta = new File(metaDir, "meta.json");
					if (meta.exists())
						meta.delete();
				}
			} catch (Exception ex) {
				anyFailed = true;
			}
		}

		if (!anyFailed)
			Toast.makeText(this, "Deleted " + entries.size() + " item(s)", Toast.LENGTH_SHORT).show();
		else
			Toast.makeText(this, "Some deletes failed", Toast.LENGTH_SHORT).show();

		adapter.enterSelectionMode(false);
		reloadList();
	}

	private void showRenameDialog(LibraryEntry entry) {
		final String current = deriveDisplayBaseName(entry);
		final android.widget.EditText input = new android.widget.EditText(this);
		input.setText(current);
		new AlertDialog.Builder(this).setTitle("Rename").setView(input).setPositiveButton("Rename", (d, which) -> {
			String newName = input.getText().toString().trim();
			if (newName.isEmpty()) {
				Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
			} else {
				renameEntry(entry, newName);
			}
		}).setNegativeButton("Cancel", null).show();
	}

	private String deriveDisplayBaseName(LibraryEntry e) {
		if (e.savedPath != null && !e.savedPath.startsWith("content://")) {
			File f = new File(e.savedPath);
			String name = f.getName();
			if (name.endsWith(".mht"))
				name = name.substring(0, name.length() - 4);
			return name;
		}
		// fallback to folder name
		return e.folderName;
	}

	private void renameEntry(LibraryEntry entry, String newBaseName) {
		File baseDir = getExternalFilesDir("mht");
		if (baseDir == null)
			baseDir = new File(getFilesDir(), "mht");

		File oldDir = new File(baseDir, entry.folderName);
		String safeFolder = newBaseName.replaceAll("[^A-Za-z0-9._-]", "_");
		File newDir = new File(baseDir, safeFolder);

		if (newDir.exists()) {
			Toast.makeText(this, "Name already exists", Toast.LENGTH_SHORT).show();
			return;
		}

		boolean moved = false;
		try {
			if (oldDir.exists() && oldDir.isDirectory()) {
				moved = oldDir.renameTo(newDir);
				// Update meta.json path/title if present
				File meta = new File(newDir, "meta.json");
				if (meta.exists()) {
					try {
						FileInputStream fis = new FileInputStream(meta);
						InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
						StringBuilder sb = new StringBuilder();
						char[] buf = new char[1024];
						int r;
						while ((r = isr.read(buf)) > 0)
							sb.append(buf, 0, r);
						try {
							isr.close();
						} catch (Exception ignored) {
						}
						try {
							fis.close();
						} catch (Exception ignored) {
						}
						JSONObject jo = new JSONObject(sb.toString());
						jo.put("title", newBaseName);
						// If meta contained a saved local path pointing to a file inside the folder, update it
						String path = jo.optString("savedPath", jo.optString("path", null));
						if (path != null && !path.startsWith("content://")) {
							File oldFile = new File(path);
							if (oldFile.exists()) {
								String newFileName = newBaseName;
								if (!newFileName.endsWith(".mht"))
									newFileName += ".mht";
								File newFile = new File(newDir, newFileName);
								// Attempt to rename internal file
								try {
									oldFile.renameTo(newFile);
									jo.put("savedPath", newFile.getAbsolutePath());
								} catch (Exception ignored) {
								}
							}
						}
						try (FileOutputStream fos = new FileOutputStream(meta);
								OutputStreamWriter os = new OutputStreamWriter(fos)) {
							os.write(jo.toString());
						}
					} catch (Exception ignored) {
					}
				}
			} else {
				// directory missing (external entry) — create a new folder to hold metadata
				if (newDir.mkdirs()) {
					JSONObject jo = new JSONObject();
					try {
						jo.put("title", newBaseName);
						jo.put("path", entry.savedPath);
						jo.put("uri", entry.url != null ? entry.url : "");
						jo.put("timestamp", System.currentTimeMillis());
						try (FileOutputStream fos = new FileOutputStream(new File(newDir, "meta.json"));
								OutputStreamWriter os = new OutputStreamWriter(fos)) {
							os.write(jo.toString());
						}
						moved = true;
					} catch (Exception ex) {
						moved = false;
					}
				}
			}
		} catch (Exception e) {
			moved = false;
		}

		if (moved) {
			Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
			adapter.enterSelectionMode(false);
			reloadList();
		} else {
			Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
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