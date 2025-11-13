package com.ccko.mhtplus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BrowserActivity extends AppCompatActivity {

	private EditText etUrl;
	private Button btnGo;
	private Button btnSave;
	private WebView webView;
	private TextView tvStatus;
	private Toolbar toolbar;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_browser);

		toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		etUrl = findViewById(R.id.et_url);
		btnGo = findViewById(R.id.btn_go);
		btnSave = findViewById(R.id.btn_save);
		webView = findViewById(R.id.webview);
		tvStatus = findViewById(R.id.tv_status);

		configureWebView();

		btnGo.setOnClickListener(v -> {
			String url = etUrl.getText().toString().trim();
			if (url.isEmpty()) {
				Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
				return;
			}
			if (!url.startsWith("http://") && !url.startsWith("https://")) {
				url = "http://" + url;
			}
			loadUrl(url);
		});

		btnSave.setOnClickListener(v -> saveCurrentPageAsMht());

		etUrl.setText("https://example.com");

		// Handle intents (open from library or external apps)
		handleIncomingIntent(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleIncomingIntent(intent);
	}

	private void configureWebView() {
		WebSettings ws = webView.getSettings();
		ws.setJavaScriptEnabled(true);
		ws.setDomStorageEnabled(true);
		ws.setBuiltInZoomControls(true);
		ws.setDisplayZoomControls(false);
		ws.setCacheMode(WebSettings.LOAD_DEFAULT);

		// Allow file access for loading local archives (may be deprecated warnings on some toolchains)
		ws.setAllowFileAccess(true);
		// The following two may be deprecated on some API levels; they help file:// based pages access resources.
		try {
			ws.setAllowFileAccessFromFileURLs(true);
			ws.setAllowUniversalAccessFromFileURLs(true);
		} catch (Exception ignored) {
		}

		webView.setWebViewClient(new WebViewClient());
		webView.setWebChromeClient(new WebChromeClient());
	}

	private void loadUrl(String url) {
		tvStatus.setText("Loading...");
		webView.loadUrl(url);
		etUrl.setText(url);
	}

	private void saveCurrentPageAsMht() {
		tvStatus.setText("Saving...");
		String url = webView.getUrl();
		if (url == null)
			url = etUrl.getText().toString().trim();
		final String finalUrl = url;

		String host = sanitizeHost(finalUrl);
		String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
		String folderName = host + "-" + timestamp;

		File baseDir = getExternalFilesDir("mht");
		if (baseDir == null) {
			baseDir = new File(getFilesDir(), "mht");
		}
		if (!baseDir.exists())
			baseDir.mkdirs();

		final File pageDir = new File(baseDir, folderName);
		if (!pageDir.exists())
			pageDir.mkdirs();

		final File outFile = new File(pageDir, "page.mht");
		final String outPath = outFile.getAbsolutePath();

		try {
			webView.saveWebArchive(outPath, false, new ValueCallback<String>() {
				@Override
				public void onReceiveValue(String value) {
					if (value != null) {
						File saved = new File(value);
						final boolean exists = saved.exists();
						try {
							writeMetadata(pageDir, finalUrl, value);
						} catch (Exception ignored) {
						}
						final String msg = exists ? "Saved: " + value : "Save reported but file missing: " + value;
						runOnUiThread(() -> {
							tvStatus.setText(msg);
							Toast.makeText(BrowserActivity.this, msg, Toast.LENGTH_LONG).show();
						});
					} else {
						runOnUiThread(() -> {
							tvStatus.setText("Save failed (null)");
							Toast.makeText(BrowserActivity.this, "Save failed", Toast.LENGTH_SHORT).show();
						});
					}
				}
			});
		} catch (Exception e) {
			tvStatus.setText("Save error: " + e.getMessage());
			Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	private void writeMetadata(File pageDir, String url, String savedPath) throws Exception {
		JSONObject meta = new JSONObject();
		meta.put("url", url == null ? "" : url);
		meta.put("savedPath", savedPath);
		meta.put("timestamp", System.currentTimeMillis());

		String title = webView.getTitle();
		if (title == null)
			title = "";
		meta.put("title", title);

		File metaFile = new File(pageDir, "metadata.json");
		try (FileOutputStream fos = new FileOutputStream(metaFile);
				OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
			writer.write(meta.toString(2));
			writer.flush();
		}
	}

	private String sanitizeHost(String url) {
		if (url == null || url.isEmpty())
			return "page";
		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			if (host == null) {
				return sanitizeFilename(url);
			}
			return sanitizeFilename(host);
		} catch (URISyntaxException e) {
			return sanitizeFilename(url);
		}
	}

	private String sanitizeFilename(String input) {
		if (input == null)
			return "page";
		return input.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	// -----------------------
	// Menu / Library entry
	// -----------------------

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_browser, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_library) {
			Intent i = new Intent(this, LibraryActivity.class);
			startActivity(i);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// -----------------------
	// Intent and open helpers
	// -----------------------

	private void handleIncomingIntent(Intent incoming) {
		if (incoming == null)
			return;

		// 1) If started with explicit extra open_mht_path
		String pathExtra = incoming.getStringExtra("open_mht_path");
		boolean readOnlyExtra = incoming.getBooleanExtra("read_only", false);

		if (pathExtra != null && !pathExtra.isEmpty()) {
			loadSavedMht(pathExtra, readOnlyExtra);
			return;
		}

		// 2) If started via ACTION_VIEW (file:// or content://)
		Uri data = incoming.getData();
		if (data != null) {
			try {
				if ("file".equalsIgnoreCase(data.getScheme())) {
					loadSavedMht(new File(data.getPath()).getAbsolutePath(), true);
				} else {
					// content:// - copy stream to app folder and open
					String copied = copyUriToAppMht(data);
					if (copied != null)
						loadSavedMht(copied, true);
					else {
						tvStatus.setText("Unable to import URI");
						Toast.makeText(this, "Unable to import URI", Toast.LENGTH_SHORT).show();
					}
				}
			} catch (Exception e) {
				tvStatus.setText("Intent handling failed");
				Toast.makeText(this, "Intent handling failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void loadSavedMht(String path, boolean readOnly) {
		if (readOnly) {
			webView.getSettings().setJavaScriptEnabled(false);
			webView.setWebViewClient(new WebViewClient() {
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					return true; // block navigation
				}

				@SuppressWarnings("deprecation")
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					return true; // fallback
				}
			});
		} else {
			webView.setWebViewClient(new WebViewClient());
			webView.getSettings().setJavaScriptEnabled(true);
		}

		File file = new File(path);
		if (!file.exists()) {
			tvStatus.setText("Saved file not found");
			Toast.makeText(this, "Saved file not found: " + path, Toast.LENGTH_SHORT).show();
			return;
		}

		tvStatus.setText("Opening: " + file.getName());

		// If file is not readable or device blocks file:// access, try copying to internal and use that
		if (!file.canRead()) {
			File internal = new File(getFilesDir(), "mht_tmp.mht");
			boolean copied = copyFile(file, internal);
			if (copied) {
				final String uriInternal = Uri.fromFile(internal).toString();
				webView.post(() -> webView.loadUrl(uriInternal));
				return;
			}
		}

		final String fileUri = Uri.fromFile(file).toString();

		// Attempt direct load; if WebView fails silently, fallback to loading raw content
		webView.post(() -> {
			try {
				webView.loadUrl(fileUri);
			} catch (Exception e) {
				// fallback: load file contents
				String content = readFileAsString(file);
				if (content != null) {
					webView.loadDataWithBaseURL("file://" + file.getParent() + "/", content, "multipart/related",
							"utf-8", null);
				} else {
					Toast.makeText(BrowserActivity.this, "Unable to load saved file", Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	// Copies content:// URI to a new per-import folder and returns the new absolute path or null
	private String copyUriToAppMht(Uri uri) {
		try {
			String host = "imported";
			String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
			String folder = host + "-" + timestamp;
			File baseDir = getExternalFilesDir("mht");
			if (baseDir == null)
				baseDir = new File(getFilesDir(), "mht");
			File pageDir = new File(baseDir, folder);
			pageDir.mkdirs();
			File outFile = new File(pageDir, "page.mht");

			try (InputStream in = getContentResolver().openInputStream(uri);
					FileOutputStream fos = new FileOutputStream(outFile)) {
				byte[] buf = new byte[8192];
				int r;
				while ((r = in.read(buf)) > 0)
					fos.write(buf, 0, r);
				fos.flush();
			}

			try {
				writeMetadata(pageDir, uri.toString(), outFile.getAbsolutePath());
			} catch (Exception ignored) {
			}

			return outFile.getAbsolutePath();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	// Helper: copy file (returns true on success)
	private boolean copyFile(File src, File dst) {
		if (src == null || !src.exists())
			return false;
		try (FileInputStream fis = new FileInputStream(src); FileOutputStream fos = new FileOutputStream(dst)) {
			byte[] buf = new byte[8192];
			int r;
			while ((r = fis.read(buf)) > 0)
				fos.write(buf, 0, r);
			fos.flush();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	// Helper: read small file into string (returns null on error or if file too large)
	private String readFileAsString(File f) {
		if (f == null || !f.exists())
			return null;
		// guard against huge files
		long max = 5 * 1024 * 1024L; // 5 MB
		if (f.length() > max)
			return null;
		try (FileInputStream fis = new FileInputStream(f);
				InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
				BufferedReader br = new BufferedReader(isr)) {
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[4096];
			int r;
			while ((r = br.read(buf)) > 0)
				sb.append(buf, 0, r);
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}