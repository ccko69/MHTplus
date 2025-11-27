package com.ccko.mhtplus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.ViewGroup;
import com.ccko.mhtplus.OcrHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.preference.PreferenceManager;

public class BrowserActivity extends AppCompatActivity {

	// 1. Constants and state
	private static final int REQ_OPEN_MHT = 1;
	private static final int FILE_CHOOSER_REQ = 0x1001;

	// Tracks whether the OCR selection UI is visible
	private boolean isOcrModeActive = false;
	private OcrOverlayView ocrOverlayView; // The new transparent view for drawing the selection box
	// 1b. NEW MEMBER VARIABLES (Place inside the BrowserActivity class declaration)
	private OcrHelper ocrHelper;
	private Bitmap webViewBitmap = null;
	// End NEW MEMBER VARIABLES

	private WebView webView;
	private Toolbar toolbar;
	private DrawerLayout drawerLayout;
	private NavigationView navigationView;
	private TextView addressTextView; // currently unused

	private boolean isFullscreen = false;
	private int originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
	private boolean isToolbarVisible = true;

	// Auto-scroll
	// keep for auto-scroll and other timed UI tasks
	private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable autoScrollRunnable = this::performAutoScrollTick;
	private final long AUTO_SCROLL_INTERVAL_MS = 50L;
	private static final String PREF_AUTOSCROLL = "pref_autoscroll_speed";
	private int autoScrollSpeed = 0;

	private ValueCallback<Uri[]> filePathCallback;

	private static final String PREF_FULLSCREEN = "pref_fullscreen";
	private static final String PREF_ORIENTATION = "pref_orientation";

	private static final String PREFS = "mhtplus_prefs";
	private static final String KEY_TARGET_LANG = "target_lang";

	private static final String TAG = "BrowserActivity";

	private String getPreferredTargetLanguage() {
		String tag = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TARGET_LANG, "en");
		return tag;
	}

	private void setPreferredTargetLanguage(String tag) {
		getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TARGET_LANG, tag).apply();
	}

	// 2. onCreate
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Edge-to-edge setup
		getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
		getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		setContentView(R.layout.activity_browser);

		// Bind views (ensure these IDs exist in activity_browser.xml)
		drawerLayout = findViewById(R.id.drawer_layout);
		navigationView = findViewById(R.id.nav_view);
		toolbar = findViewById(R.id.toolbar);
		webView = findViewById(R.id.webview);
		// Optional status / address view (keep commented if unused)
		// addressTextView = findViewById(R.id.address_text);

		// Initialize OCR helper and overlay AFTER views are inflated
		// Ensure OcrHelper has a constructor that accepts Context or adapt accordingly
		try {
			ocrHelper = new OcrHelper(); // if your OcrHelper requires context change accordingly
		} catch (Exception e) {
			Log.w("BrowserActivity", "Failed to create OcrHelper: " + e.getMessage());
			ocrHelper = null;
		}

		// The overlay should be present in layout (FrameLayout over WebView). Use R.id.ocr_overlay
		ocrOverlayView = findViewById(R.id.ocr_overlay);
		if (ocrOverlayView != null) {
			if (ocrHelper != null) {
				ocrOverlayView.setOcrHelper(ocrHelper);
			} else {
				Log.w("BrowserActivity", "ocrHelper is null; overlay will not perform OCR calls.");
			}
			ocrOverlayView.setVisibility(View.GONE); // hidden until OCR mode enabled
		} else {
			Log.w("BrowserActivity", "ocrOverlayView not found in layout (R.id.ocr_overlay).");
		}
		ocrOverlayView.setSelectionListener(new OcrOverlayView.SelectionListener() {
			@Override
			public void onSelectionFinished() {
				if (webView != null)
					webView.setOnTouchListener(null);
			}

			@Override
			public void onSelectionCancelled() {
				if (webView != null)
					webView.setOnTouchListener(null);
			}
		});

		// Toolbar setup (optional, can be hidden)
		if (toolbar != null) {
			toolbar.setNavigationIcon(android.R.drawable.ic_menu_more);
			int padding = (int) (8 * getResources().getDisplayMetrics().density);
			toolbar.setPadding(padding, padding, padding, padding);
			toolbar.setNavigationOnClickListener(v -> {
				if (drawerLayout != null)
					drawerLayout.openDrawer(GravityCompat.START);
			});
			setSupportActionBar(toolbar);
			if (getSupportActionBar() != null)
				getSupportActionBar().setDisplayShowTitleEnabled(false);
		}

		// start hidden by default
		if (toolbar != null)
			toolbar.setVisibility(View.GONE);

		// Auto-scroll SeekBar wiring
		if (navigationView != null) {
			try {
				MenuItem autoItem = navigationView.getMenu().findItem(R.id.nav_autoscroll);
				if (autoItem != null && autoItem.getActionView() != null) {
					View actionView = autoItem.getActionView();
					SeekBar sb = actionView.findViewById(R.id.seek_autoscroll);
					TextView label = actionView.findViewById(R.id.autoscroll_label);
					final int initialProgress = 0;
					if (sb != null) {
						sb.setProgress(initialProgress);
						autoScrollSpeed = mapProgressToSpeed(initialProgress);
					}
					if (label != null)
						label.setText(String.valueOf(initialProgress));
					final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
					if (sb != null) {
						sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
							@Override
							public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
								prefs.edit().putInt(PREF_AUTOSCROLL, progress).apply();
								if (label != null)
									label.setText(String.valueOf(progress));
								autoScrollSpeed = mapProgressToSpeed(progress);
								if (autoScrollSpeed > 0)
									startAutoScroll();
								else
									stopAutoScroll();
							}

							@Override
							public void onStartTrackingTouch(SeekBar seekBar) {
							}

							@Override
							public void onStopTrackingTouch(SeekBar seekBar) {
							}
						});
					}
				}
			} catch (Exception ignored) {
			}
		}

		if (navigationView != null)
			navigationView.setNavigationItemSelectedListener(this::onNavItemSelected);

		loadPreferences();
		setupWebView();
		handleIncomingIntent(getIntent());

		// Optional: schedule toolbar hide — remove or comment this line if you want toolbar always visible
		scheduleToolbarHide();

		// Root-level gesture to reveal toolbar
		View root = findViewById(android.R.id.content);
		final android.view.GestureDetector rootGesture = new android.view.GestureDetector(this,
				new android.view.GestureDetector.SimpleOnGestureListener() {

					@Override
					public boolean onSingleTapUp(android.view.MotionEvent e) {
						// Guard against accidental toolbar hide in OCR mode
						if (isOcrModeActive)
							return true;

						if (isToolbarVisible) {
							scheduleToolbarHide();
						} else {
							showToolbarAnimated();
							scheduleToolbarHide();
						}
						return true;
					}

					@Override
					public boolean onDown(android.view.MotionEvent e) {
						// Consume touch event if OCR mode is active to prevent WebView glitches
						if (isOcrModeActive)
							return true;
						return true;
					}

					@Override
					public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx,
							float vy) {
						// Guard against fling gesture being used in OCR mode
						if (isOcrModeActive)
							return false;

						if (e1 != null && e2 != null && (e2.getY() - e1.getY()) > 80) {
							showToolbarAnimated();
							scheduleToolbarHide();
							return true;
						}
						return false;
					}

				});
		if (root != null)
			root.setOnTouchListener((v, ev) -> rootGesture.onTouchEvent(ev));
	}

	// 3. Lifecycle
	@Override
	protected void onResume() {
		super.onResume();
		if (webView != null)
			webView.onResume();
	}

	@Override
	protected void onPause() {
		if (webView != null)
			webView.onPause();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// 1) Tear down overlay translation resources first
		try {
			if (ocrOverlayView != null) {
				ocrOverlayView.closeTranslationResources();
			}
		} catch (Exception ignored) {
		}

		// 2) Close OCR helper if it exposes a close/cleanup (defensive)
		try {
			if (ocrHelper != null) {
				ocrHelper.close();
			}
		} catch (Exception ignored) {
		}

		// 3) Cancel any pending UI handler callbacks (auto-scroll etc.)
		try {
			if (uiHandler != null) {
				uiHandler.removeCallbacksAndMessages(null);
			}
		} catch (Exception ignored) {
		}

		// 4) Remove toolbar callbacks/listeners so animations won't reference this Activity
		try {
			if (toolbar != null) {
				toolbar.removeCallbacks(null);
				toolbar.setOnMenuItemClickListener(null);
				toolbar.setNavigationOnClickListener(null);
			}
		} catch (Exception ignored) {
		}

		// 5) Safely destroy the WebView (stop loading / clear clients first)
		try {
			if (webView != null) {
				webView.stopLoading();
				webView.setWebChromeClient(null);
				webView.setWebViewClient(null);
				webView.removeAllViews();
				webView.clearHistory();
				webView.destroy();
				webView = null;
			}
		} catch (Exception ignored) {
		}

		// 6) Other UI references
		try {
			if (ocrOverlayView != null) {
				ocrOverlayView.setOcrHelper(null);
				ocrOverlayView = null;
			}
		} catch (Exception ignored) {
		}

		super.onDestroy();
	}

	// 4. onActivityResult
	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		// Library or picker result
		if (requestCode == REQ_OPEN_MHT && resultCode == RESULT_OK && data != null) {

			// A) From Library: explicit file path
			String path = data.getStringExtra("open_mht_path");
			if (path != null && !path.isEmpty()) {
				File f = new File(path);
				if (f.exists()) {
					loadSavedMht(path, true);
				} else {
					Toast.makeText(this, "Selected file not found", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			// B) From picker: URI in data (The external file selection)
			Uri uri = data.getData();
			if (uri != null) {
				final int takeFlags = data.getFlags()
						& (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				try {
					// Attempt to take persistent permission for the URI
					getContentResolver().takePersistableUriPermission(uri, takeFlags);
				} catch (Exception ignored) {
				}

				// Dedup-aware: copy/dedupe the external URI to the app's internal MHT folder.
				// This returns the internal file path string if successful.
				String resolved = copyUriToAppMht(uri);

				if (resolved != null) {
					// CRITICAL FIX: We remove the conditional scheme check.
					// Any file that has been successfully saved/deduped (resolved != null)
					// MUST be loaded via loadSavedMht to ensure read-only settings are applied.
					loadSavedMht(resolved, true);
				} else {
					// Fallback for failed copy: attempt to load the raw external URI directly
					try {
						webView.loadUrl(uri.toString());
					} catch (Exception e) {
						Toast.makeText(this, "Unable to open selected file", Toast.LENGTH_SHORT).show();
					}
				}
				return;
			}
		}

		// WebView file chooser result
		if (requestCode == FILE_CHOOSER_REQ) {
			if (filePathCallback == null)
				return;
			Uri[] results = null;
			if (resultCode == RESULT_OK && data != null) {
				Uri dataUri = data.getData();
				if (dataUri != null)
					results = new Uri[] { dataUri };
			}
			filePathCallback.onReceiveValue(results);
			filePathCallback = null;
		}
	}

	// 5. WebView setup
	private void setupWebView() {
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true); // we toggle off for read-only loads
		settings.setDomStorageEnabled(true);
		settings.setBuiltInZoomControls(true);
		settings.setDisplayZoomControls(false);
		settings.setLoadWithOverviewMode(true);
		settings.setUseWideViewPort(true);
		settings.setAllowFileAccess(true);
		try {
			settings.setAllowFileAccessFromFileURLs(true);
			settings.setAllowUniversalAccessFromFileURLs(true);
		} catch (Exception ignored) {
		}
		settings.setSupportMultipleWindows(false);

		webView.setWebViewClient(new WebViewClient());
		webView.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
					android.os.Message resultMsg) {
				return false; // block popups
			}

			@Override
			public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> cb,
					WebChromeClient.FileChooserParams params) {
				BrowserActivity.this.filePathCallback = cb;
				Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
				contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
				contentSelectionIntent.setType("*/*");

				Intent[] intentArray;
				try {
					Intent platformIntent = params.createIntent();
					intentArray = new Intent[] { platformIntent };
				} catch (Exception e) {
					intentArray = new Intent[0];
				}

				Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
				chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
				chooserIntent.putExtra(Intent.EXTRA_TITLE, "MHT+ Browser");
				chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

				startActivityForResult(chooserIntent, FILE_CHOOSER_REQ);
				return true;
			}
		});
	}

	// 6. URL and saved file loading
	private void loadUrl(String url) {
		if (webView == null)
			return;
		webView.post(() -> webView.loadUrl(url));
	}

	private void loadSavedMht(String path, boolean readOnly) {
		if (path == null)
			return;

		// Case A: Handle content:// URIs (external .mht files)
		if (path.startsWith("content://")) {
			Uri uri = Uri.parse(path);
			try (InputStream in = getContentResolver().openInputStream(uri)) {
				// Copy into a temporary file in cache so WebView can load it via file://
				File tempFile = new File(getCacheDir(), "imported.mht");
				try (FileOutputStream out = new FileOutputStream(tempFile)) {
					byte[] buf = new byte[8192];
					int r;
					while ((r = in.read(buf)) > 0)
						out.write(buf, 0, r);
				}
				final String tempUri = Uri.fromFile(tempFile).toString();
				// Continue with normal load flow using the temp file path
				applyReadonlyOrNormalAndLoad(tempUri, readOnly);
			} catch (Exception e) {
				Toast.makeText(this, "Unable to load external MHT", Toast.LENGTH_SHORT).show();
			}
			return;
		}

		// Case B: Normal file path
		File file = new File(path);
		if (!file.exists()) {
			Toast.makeText(this, "Saved file not found: " + path, Toast.LENGTH_SHORT).show();
			return;
		}

		setStatus("Opening: " + file.getName());

		final String fileUri = Uri.fromFile(file).toString();
		applyReadonlyOrNormalAndLoad(fileUri, readOnly);
	}

	private void applyReadonlyOrNormalAndLoad(final String uriToLoad, boolean readOnly) {
		if (webView == null)
			return;

		// --- Configuration based on readOnly flag ---
		if (readOnly) {
			WebSettings s = webView.getSettings();

			// Core read-only settings
			s.setJavaScriptEnabled(false);
			s.setDomStorageEnabled(false);
			s.setLoadsImagesAutomatically(true);

			// Keep scrolling and zoom
			s.setSupportZoom(true);
			s.setBuiltInZoomControls(true);
			s.setDisplayZoomControls(false);

			// Disable risky file access from JS if available
			try {
				s.setAllowFileAccessFromFileURLs(false);
				s.setAllowUniversalAccessFromFileURLs(false);
			} catch (Exception ignored) {
			}

			// Block navigation (links, redirects, form submissions)
			webView.setWebViewClient(new WebViewClient() {
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					// Block all navigation attempts in read-only mode
					return true;
				}

				@Override
				@SuppressWarnings("deprecation")
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					return true;
				}

				@Override
				public void onPageFinished(WebView view, String url) {
					// allow text selection after page finishes loading
					view.setLongClickable(true);
					view.setHapticFeedbackEnabled(true);
				}
			});

			// Block popups / new windows
			webView.setWebChromeClient(new WebChromeClient() {
				@Override
				public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
						android.os.Message resultMsg) {
					return false;
				}
			});

			// Allow text selection but prevent context actions that could submit or navigate.
			// We enable long-click/selection but prevent default context menu navigation by intercepting selection actions.
			webView.setOnLongClickListener(v -> {
				// Let WebView handle selection (return false to allow selection)
				return false;
			});
			webView.setLongClickable(true);
			webView.setHapticFeedbackEnabled(true);

			// Prevent focusable input (forms) from accepting input
			webView.setFocusable(false);
			webView.setFocusableInTouchMode(false);

		} else {
			// Normal interactive mode
			WebSettings s = webView.getSettings();
			s.setJavaScriptEnabled(true);
			s.setDomStorageEnabled(true);
			s.setSupportZoom(true);
			s.setBuiltInZoomControls(true);
			s.setDisplayZoomControls(false);

			webView.setWebViewClient(new WebViewClient());
			webView.setWebChromeClient(new WebChromeClient());

			// Restore ability to focus inputs
			webView.setFocusable(true);
			webView.setFocusableInTouchMode(true);
			webView.setLongClickable(true);
			webView.setHapticFeedbackEnabled(true);
		}

		// Finally, load the requested file URI on the UI thread
		webView.post(() -> {
			try {
				// CRITICAL FIX: The only correct way to load a MHT archive is via loadUrl.
				// The previous catch block contained faulty MHT parsing logic.
				webView.loadUrl(uriToLoad);
			} catch (Exception e) {
				// Simple error reporting if the WebView fundamentally fails to load the URL
				Toast.makeText(BrowserActivity.this, "FATAL: WebView unable to load file URI.", Toast.LENGTH_LONG)
						.show();
				e.printStackTrace();
			}
		});
	}

	// 7. Incoming intents
	private void handleIncomingIntent(Intent incoming) {
		if (incoming == null)
			return;

		// Case A: Open a normal URL
		String openUrlExtra = incoming.getStringExtra("open_url");
		if (openUrlExtra != null && !openUrlExtra.isEmpty()) {
			loadUrl(openUrlExtra);
			return;
		}

		// Case B: Open a saved .mht file path
		String pathExtra = incoming.getStringExtra("open_mht_path");
		boolean readOnlyExtra = incoming.getBooleanExtra("read_only", true);
		if (pathExtra != null && !pathExtra.isEmpty()) {
			loadSavedMht(pathExtra, readOnlyExtra);
			return;
		}

		// Case C: Handle data URI (from picker or external launch)
		Uri data = incoming.getData();
		if (data != null) {
			try {
				if ("file".equalsIgnoreCase(data.getScheme())) {
					// Direct file path -> open file directly
					loadSavedMht(new File(data.getPath()).getAbsolutePath(), true);
				} else {
					// For launch/open intents: open content URIs directly without registering them in the library.
					// loadSavedMht already handles content:// by copying to a temp cache file for WebView.
					loadSavedMht(data.toString(), true);
				}
			} catch (Exception e) {
				Toast.makeText(this, "Intent handling failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
			}
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleIncomingIntent(intent);
	}

	// 8. Toolbar visibility
	public void scheduleToolbarHide() {

		// NOTE: uses 2000ms, not TOOLBAR_HIDE_DELAY_MS; unify later if needed.
	}

	public void cancelToolbarHide() {

	}

	public void hideToolbarAnimated() {
		if (toolbar == null)
			return;

		// animate then set GONE so layout won't reserve space
		toolbar.animate().translationY(-toolbar.getHeight()).alpha(0f).setDuration(300).withEndAction(() -> {
			toolbar.setTranslationY(-toolbar.getHeight());
			toolbar.setAlpha(0f);
			toolbar.setVisibility(View.GONE);
		}).start();

		if (isFullscreen)
			setSystemBarVisibility(false);
	}

	public void showToolbarAnimated() {
		if (toolbar == null)
			return;

		// Make visible before animation so it occupies layout space immediately
		toolbar.setVisibility(View.VISIBLE);
		toolbar.setTranslationY(-toolbar.getHeight());
		toolbar.setAlpha(0f);

		toolbar.animate().translationY(0).alpha(1f).setDuration(300).withEndAction(() -> {
			toolbar.setTranslationY(0);
			toolbar.setAlpha(1f);
		}).start();

		setSystemBarVisibility(true);
		invalidateOptionsMenu(); // refresh menu to reflect OCR mode
	}

	// 9. Picker import and dedup logic
	private boolean isMhtUri(Uri uri) {
		if (uri == null)
			return false;
		// MIME check
		try {
			String type = getContentResolver().getType(uri);
			if (type != null && type.equalsIgnoreCase("multipart/related"))
				return true;
		} catch (Exception ignored) {
		}
		// Display name extension check
		try (android.database.Cursor c = getContentResolver().query(uri,
				new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
			if (c != null && c.moveToFirst()) {
				String name = c.getString(0);
				if (name != null) {
					String lower = name.toLowerCase(Locale.US);
					return lower.endsWith(".mht") || lower.endsWith(".mhtml");
				}
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	private String registerExternalMht(Uri uri) {
		if (uri == null)
			return null;
		try {
			// Build a stable folder name based on display name
			String displayName = "external";
			try (android.database.Cursor c = getContentResolver().query(uri,
					new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
				if (c != null && c.moveToFirst()) {
					String n = c.getString(0);
					if (n != null && !n.trim().isEmpty())
						displayName = n.replaceAll("[^A-Za-z0-9._-]", "_");
				}
			} catch (Exception ignored) {
			}

			File baseDir = getExternalFilesDir("mht");
			if (baseDir == null)
				baseDir = new File(getFilesDir(), "mht");

			String folder = "link-" + displayName;
			File pageDir = new File(baseDir, folder);
			pageDir.mkdirs();

			// Store uri string as both "uri" and "path" (path may be content://)
			writeMetadata(pageDir, uri.toString(), uri.toString());
			return uri.toString();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	// If .mht: do not copy; just register metadata and return content URI string. Else: copy and return local path.
	private String copyUriToAppMht(Uri uri) {
		if (uri == null)
			return null;
		try {
			if (isMhtUri(uri)) {
				return registerExternalMht(uri); // no physical copy
			}

			// Non-.mht content: copy into app storage (legacy behavior)
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

			writeMetadata(pageDir, uri.toString(), outFile.getAbsolutePath());
			return outFile.getAbsolutePath();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	// 10. File IO helpers
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

	private String readFileAsString(File f) {
		if (f == null || !f.exists())
			return null;
		long max = 10 * 1024 * 1024L;
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

	// 11. Fullscreen and system bar control
	public void setFullscreenState(boolean fullscreen) {
		this.isFullscreen = fullscreen;
		WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(),
				getWindow().getDecorView());

		if (fullscreen) {
			controller.hide(WindowInsetsCompat.Type.systemBars());
			getWindow().setDecorFitsSystemWindows(false);
			getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
			getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		} else {
			controller.show(WindowInsetsCompat.Type.systemBars());
			getWindow().setDecorFitsSystemWindows(true);
			getWindow().setStatusBarColor(android.graphics.Color.BLACK);
			getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
			controller.setAppearanceLightStatusBars(true); // NOTE: may not match theme; keep if you prefer visible icons.
		}
	}

	private void setSystemBarVisibility(boolean visible) {
		Window window = getWindow();
		View decorView = window.getDecorView();
		WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, decorView);
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

		if (visible) {
			getWindow().setDecorFitsSystemWindows(true);
			controller.show(WindowInsetsCompat.Type.systemBars());
		} else {
			getWindow().setDecorFitsSystemWindows(false);
			controller.hide(WindowInsetsCompat.Type.systemBars());
		}
	}

	private void toggleOrientation() {
		if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		} else {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		}
		originalOrientation = getRequestedOrientation();
		savePreferences();
		Toast.makeText(this, "Orientation Toggled", Toast.LENGTH_SHORT).show();
	}

	// 12. Preferences
	private void loadPreferences() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		isFullscreen = prefs.getBoolean(PREF_FULLSCREEN, false);
		originalOrientation = prefs.getInt(PREF_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

		setRequestedOrientation(originalOrientation);

		if (navigationView != null) {
			MenuItem fullscreenItem = navigationView.getMenu().findItem(R.id.nav_fullscreen);
			if (fullscreenItem != null)
				fullscreenItem.setChecked(isFullscreen);
		}

		if (isFullscreen && !isToolbarVisible)
			setSystemBarVisibility(false);
	}

	private void savePreferences() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(PREF_FULLSCREEN, isFullscreen);
		editor.putInt(PREF_ORIENTATION, originalOrientation);
		editor.apply();
	}

	// 13. Saving MHT (from URL only)
	private void saveCurrentPageAsMht() {
		if (webView.getUrl() == null) {
			Toast.makeText(this, "Cannot save page: No URL loaded.", Toast.LENGTH_SHORT).show();
			return;
		}
		if (webView.getUrl().startsWith("file://")) {
			Toast.makeText(this, "Saving local files (file://) is not supported.", Toast.LENGTH_SHORT).show();
			return;
		}

		String outPath = getArchiveFileName();
		if (outPath == null) {
			Toast.makeText(this, "Archive dir unavailable.", Toast.LENGTH_SHORT).show();
			return;
		}

		webView.saveWebArchive(outPath, false, new ValueCallback<String>() {
			@Override
			public void onReceiveValue(String value) {
				if (value != null) {
					// NOTE: consider writing meta.json here to unify library entries for saved pages.
					Toast.makeText(BrowserActivity.this, "Page saved successfully.", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(BrowserActivity.this, "Failed to save page.", Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	private String getArchiveFileName() {
		File dir = getArchiveDir();
		if (dir == null)
			return null;
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
		String ts = sdf.format(new Date());
		return new File(dir, ts + ".mht").getAbsolutePath();
	}

	private File getArchiveDir() {
		File base = getExternalCacheDir();
		if (base == null) {
			Toast.makeText(this, "Failed to get archive directory.", Toast.LENGTH_SHORT).show();
			return null;
		}
		File archives = new File(base, "archives");
		if (!archives.exists())
			archives.mkdirs();
		return archives;
	}

	// 14. Metadata helpers
	private String getMetadataFile(File pageDir) {
		return new File(pageDir, "meta.json").getAbsolutePath();
	}

	private void writeMetadata(File pageDir, String uri, String path) throws Exception {
		JSONObject obj = new JSONObject();
		obj.put("uri", uri);
		obj.put("path", path);
		obj.put("title", webView != null && webView.getTitle() != null ? webView.getTitle() : "Untitled");
		obj.put("timestamp", System.currentTimeMillis());
		try (FileOutputStream fos = new FileOutputStream(getMetadataFile(pageDir));
				OutputStreamWriter os = new OutputStreamWriter(fos)) {
			os.write(obj.toString());
		}
	}

	private void setStatus(String s) {
		// optional status UI — currently unused
	}

	private String copyStream(InputStream in, File pageDir) {
		// Legacy helper; commented as "not used" but kept for reference
		if (pageDir == null)
			return null;
		File outFile = new File(pageDir, "index.mht");
		try (FileOutputStream fos = new FileOutputStream(outFile)) {
			byte[] buf = new byte[8192];
			int r;
			while ((r = in.read(buf)) > 0)
				fos.write(buf, 0, r);
			fos.flush();
			try {
				writeMetadata(pageDir, webView != null ? webView.getUrl() : "", outFile.getAbsolutePath());
			} catch (Exception ignored) {
			}
			return outFile.getAbsolutePath();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	// 15. Menu / Nav handling
	public boolean onNavItemSelected(@NonNull MenuItem item) {
		if (drawerLayout != null)
			drawerLayout.closeDrawer(GravityCompat.START);
		int id = item.getItemId();

		if (id == R.id.nav_open) {
			Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			pick.setType("*/*");
			pick.addCategory(Intent.CATEGORY_OPENABLE);
			String[] mimeTypes = { "multipart/related", "text/html", "application/octet-stream" };
			pick.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
			startActivityForResult(pick, REQ_OPEN_MHT);
			return true;
		}

		if (id == R.id.nav_library) {
			Intent i = new Intent(this, LibraryActivity.class);
			startActivityForResult(i, REQ_OPEN_MHT);
			return true;
		}

		// NEW: Enter OCR Translation Mode
		if (id == R.id.nav_translate_image) {
			// This is the trigger to switch modes and reveal the toolbar/overlay
			toggleOcrMode(true);
			// We already closed the drawer above, but we return true to mark the event as handled
			return true;
		}

		if (id == R.id.nav_save) {
			saveCurrentPageAsMht();
			return true;
		}

		if (id == R.id.nav_orientation) {
			toggleOrientation();
			return true;
		}

		if (id == R.id.nav_fullscreen) {
			setFullscreenState(!isFullscreen);
			item.setChecked(isFullscreen);
			savePreferences();
			return true;
		}

		if (id == R.id.nav_more) {
			Toast.makeText(this, "More options not yet implemented.", Toast.LENGTH_SHORT).show();
			return true;
		}

		return false;
	}
	// 4. NEW METHODS (Place these anywhere outside of existing methods)

	// Call this when the nav item "Translate image" is pressed
	public void enterOcrMode() {
		toggleOcrMode(true);
		// Capture a fresh screenshot for the overlay each time we enter OCR
		captureWebViewScreenshotAndShowOverlay();
	}

	/**
	* Toggles the application state between standard browsing mode and OCR selection mode.
	*
	* @param activate If true, enters OCR mode; if false, returns to browsing mode.
	*/
	public void toggleOcrMode(boolean activate) {
		if (isOcrModeActive == activate)
			return;
		isOcrModeActive = activate;

		if (ocrOverlayView != null) {
			ocrOverlayView.setVisibility(activate ? View.VISIBLE : View.GONE);
			// Do not disable webView by default - selection controls that
		}

		if (activate) {
			// Toolbar visible when entering OCR mode; capture happens when Select Area is tapped
			showToolbarAnimated();
		} else {
			// cleanup overlay selections and translator if needed
			if (ocrOverlayView != null) {
				ocrOverlayView.cancelSelectionIfAny();
			}
			hideToolbarAnimated();
		}

		invalidateOptionsMenu();
	}

	/**
	 * Inflates the appropriate menu (default or OCR) based on the current mode.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.clear();
		if (isOcrModeActive) {
			getMenuInflater().inflate(R.menu.menu_ocr, menu);
		} else {
			// inflate default toolbar menu if you want regular actions here
			// getMenuInflater().inflate(R.menu.menu_normal, menu); // optional
		}
		return true;
	}

	/**
	 * Handles clicks on the visible toolbar items (only used in OCR mode).
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();

		if (id == R.id.action_select_area) {
			// Toggle selection state
			if (ocrOverlayView != null) {
				if (ocrOverlayView.isSelecting()) {
					// If currently selecting, cancel selection and restore interaction
					ocrOverlayView.cancelSelectionIfAny();
					if (webView != null)
						webView.setOnTouchListener(null);
					Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
				} else {
					// Enter selection mode: capture a fresh screenshot and set up overlay
					captureWebViewScreenshotAndShowOverlay();

					// Prevent webView scrolling while selection is active by querying overlay state
					if (webView != null) {
						webView.setOnTouchListener((v, ev) -> ocrOverlayView.isSelecting());
					}

					Toast.makeText(this, "Select area: drag on screen", Toast.LENGTH_SHORT).show();
				}
			}
			return true;
		}

		if (id == R.id.action_translate_language) {
			showLanguagePickerDialog();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private final String[] LANGUAGE_DISPLAY = new String[] { "Auto-detect", "English", "Spanish", "French", "German",
			"Chinese (Simplified)", "Japanese" };
	private final String[] LANGUAGE_TAGS = new String[] { "auto", "en", "es", "fr", "de", "zh", "ja" };

	private void showLanguagePickerDialog() {
		final int currentIndex = Math.max(0,
				java.util.Arrays.asList(LANGUAGE_TAGS).indexOf(getPreferredTargetLanguage()));
		new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Translate target language")
				.setSingleChoiceItems(LANGUAGE_DISPLAY, currentIndex, (dialog, which) -> {
					String selectedTag = LANGUAGE_TAGS[which];
					setPreferredTargetLanguage(selectedTag);
					dialog.dismiss();
					// Immediately propagate language to overlay if present
					if (ocrOverlayView != null)
						ocrOverlayView.setTargetLanguageTag(selectedTag);
					Toast.makeText(this, "Language set: " + LANGUAGE_DISPLAY[which], Toast.LENGTH_SHORT).show();
				}).setNegativeButton("Cancel", null).show();
	}

	/**
	 * Capture the visible WebView viewport and hand the bitmap to the overlay for selection.
	 * This runs on the WebView's thread via post() so it is safe to draw.
	 */
	private void captureWebViewScreenshotAndShowOverlay() {
		if (webView == null) {
			Toast.makeText(this, "WebView not ready for capture.", Toast.LENGTH_SHORT).show();
			return;
		}
		if (ocrOverlayView == null) {
			Log.w(TAG, "OCR overlay missing");
			return;
		}

		// Post to ensure WebView has measured layout and drawing occurs on UI thread
		webView.post(() -> {
			try {
				int contentWidth = Math.max(1, webView.getWidth());
				int contentHeight = Math.max(1, webView.getHeight());

				Bitmap webViewBitmap = Bitmap.createBitmap(contentWidth, contentHeight, Bitmap.Config.ARGB_8888);
				Canvas canvas = new Canvas(webViewBitmap);

				// Draw the WebView into the bitmap (visible viewport)
				webView.draw(canvas);

				// Pass bitmap to overlay (no redundant cast) and show overlay
				ocrOverlayView.prepareForSelection(webViewBitmap);
				ocrOverlayView.setVisibility(View.VISIBLE);

				// Ensure the overlay uses the current language target
				String tag = getPreferredTargetLanguage();
				if (tag == null || tag.isEmpty())
					tag = "en";
				ocrOverlayView.setTargetLanguageTag(tag);

				Toast.makeText(this, "Screen captured. Select the text area.", Toast.LENGTH_SHORT).show();
			} catch (Exception e) {
				Log.e(TAG, "Failed to capture WebView screenshot: " + e.getMessage(), e);
				Toast.makeText(this, "Capture failed.", Toast.LENGTH_SHORT).show();
			}
		});
	}

	/**
	 * Captures the current content of the WebView into a Bitmap.
	 * This bitmap is stored in webViewBitmap for later cropping by the overlay view.
	 */
	private void captureWebViewScreenshot() {
		// 1. Determine the size of the content to capture
		int contentWidth = webView.getWidth();
		int contentHeight = webView.getHeight();

		if (contentWidth <= 0 || contentHeight <= 0) {
			Toast.makeText(this, "WebView not ready for capture.", Toast.LENGTH_SHORT).show();
			return;
		}

		// 2. Create a Bitmap to hold the image
		// Note: This only captures the visible portion in older API levels,
		// but on modern devices and with this method, it often captures the full visible view.
		webViewBitmap = Bitmap.createBitmap(contentWidth, contentHeight, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(webViewBitmap);

		// 3. Draw the WebView onto the Canvas
		// We explicitly draw the WebView to the canvas, excluding the toolbar area if it overlaps.
		webView.draw(canvas);

		if (webViewBitmap != null) {
			// Capture successful, now tell the overlay view to start selection
			Toast.makeText(this, "Screen captured. Select the text area.", Toast.LENGTH_LONG).show();
			// Force the OcrOverlayView to process the new bitmap
			// We will implement this 'prepareForSelection' method in the OcrOverlayView class next.
			((OcrOverlayView) ocrOverlayView).prepareForSelection(webViewBitmap);
		} else {
			Log.e("Capture", "Failed to create Bitmap from WebView.");
			Toast.makeText(this, "Capture failed.", Toast.LENGTH_SHORT).show();
		}
	}
	// End NEW METHODS

	// 16. Auto-scroll
	private int mapProgressToSpeed(int progress) {
		if (progress <= 0)
			return 0;
		int maxPixelsPerTick = 5;
		return Math.max(1, (progress * maxPixelsPerTick) / 100);
	}

	private void performAutoScrollTick() {
		try {
			if (webView == null)
				return;
			if (autoScrollSpeed <= 0) {
				stopAutoScroll();
				return;
			}
			final int dy = autoScrollSpeed;
			webView.post(() -> webView.scrollBy(0, dy));
			uiHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_INTERVAL_MS);
		} catch (Exception ignored) {
		}
	}

	private void startAutoScroll() {
		cancelAutoScroll();
		if (autoScrollSpeed <= 0)
			return;
		uiHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_INTERVAL_MS);
	}

	private void stopAutoScroll() {
		uiHandler.removeCallbacks(autoScrollRunnable);
	}

	private void cancelAutoScroll() {
		stopAutoScroll();
	}

}
