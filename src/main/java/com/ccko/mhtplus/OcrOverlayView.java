package com.ccko.mhtplus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.nl.translate.Translation;

import java.util.Locale;

/**
* OcrOverlayView: A custom View placed on top of the WebView to handle
* user selection (cropping) and display of translated text results.
*/
public class OcrOverlayView extends View implements OcrHelper.OcrCallback {

	private static final String TAG = "OcrOverlayView";

	// --- State Variables ---
	private Bitmap sourceBitmap;

	private final Paint clearPaint = new Paint();
	private final Paint selectionPaint = new Paint();
	private final Paint textPaint = new Paint();

	// Selection tracking
	private float startX = 0f;
	private float startY = 0f;
	private float endX = 0f;
	private float endY = 0f;
	private boolean isSelecting = false;

	// ML Kit translation support
	private LanguageIdentifier langIdentifier;
	private Translator currentTranslator;
	private String pendingTranslationText = null;

	// Result display
	private String recognizedText = "Tap 'Select Area' and drag to capture text.";
	private String translatedText = "";
	private final Rect textDisplayBounds = new Rect();

	private OcrHelper ocrHelper;

	// Current translation target tag (e.g., "en", "es")
	private String targetLangTag = "en";

	// Listener for selection lifecycle events so Activity can restore touch handling
	public interface SelectionListener {
		void onSelectionFinished();

		void onSelectionCancelled();
	}

	private SelectionListener selectionListener;

	public void setSelectionListener(SelectionListener l) {
		this.selectionListener = l;
	}

	// --- Constructors ---
	public OcrOverlayView(Context context) {
		this(context, null);
	}

	public OcrOverlayView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	private void init() {
		// Language identifier
		// Replace any LanguageIdentificationOptions usage with this:
		try {
			langIdentifier = com.google.mlkit.nl.languageid.LanguageIdentification.getClient();
		} catch (NoSuchMethodError | NoClassDefFoundError ex) {
			// Fallback: older package or version mismatch — log and null out so app doesn't crash
			Log.w("OcrOverlayView", "LanguageIdentifier not available: " + ex.getMessage());
			langIdentifier = null;
		}
		;

		// Paint for the transparent overlay outside the selection box
		clearPaint.setColor(Color.argb(180, 0, 0, 0)); // Semi-transparent black

		// Paint for the selection rectangle (border)
		selectionPaint.setColor(Color.RED);
		selectionPaint.setStyle(Paint.Style.STROKE);
		selectionPaint.setStrokeWidth(5f);

		// Paint for displaying recognized and translated text
		textPaint.setColor(Color.WHITE);
		textPaint.setTextSize(30f);
		textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
	}

	// --- External Setter ---

	/**
	* Called by BrowserActivity when a new screenshot is ready to be selected.
	*/
	public void prepareForSelection(Bitmap bitmap) {
		this.sourceBitmap = bitmap;
		this.recognizedText = "Drag to select the area containing text.";
		this.translatedText = "";
		this.startX = this.startY = this.endX = this.endY = 0;
		this.isSelecting = false;
		invalidate();
	}

	/**
	* Sets the OcrHelper instance for communication.
	*/
	public void setOcrHelper(OcrHelper helper) {
		this.ocrHelper = helper;
	}

	// --- Drawing Logic ---

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (sourceBitmap == null) {
			drawInstructionText(canvas, recognizedText);
			return;
		}

		canvas.drawBitmap(sourceBitmap, 0, 0, null);

		float left = Math.min(startX, endX);
		float top = Math.min(startY, endY);
		float right = Math.max(startX, endX);
		float bottom = Math.max(startY, endY);

		if (isSelecting || (right > left && bottom > top)) {
			// Draw mask regions
			canvas.drawRect(0, 0, getWidth(), top, clearPaint); // Top
			canvas.drawRect(0, bottom, getWidth(), getHeight(), clearPaint); // Bottom
			canvas.drawRect(0, top, left, bottom, clearPaint); // Left
			canvas.drawRect(right, top, getWidth(), bottom, clearPaint); // Right

			// Draw the selection border
			canvas.drawRect(left, top, right, bottom, selectionPaint);
		} else {
			// Draw full mask if nothing is selected yet
			canvas.drawRect(0, 0, getWidth(), getHeight(), clearPaint);
		}

		if (translatedText.isEmpty()) {
			drawInstructionText(canvas, recognizedText);
		} else {
			drawResultText(canvas);
		}
	}

	// add a public API
	public boolean isSelecting() {
		return isSelecting;
	}

	// Cancel any active selection and reset overlay state
	public void cancelSelectionIfAny() {
		if (isSelecting) {
			isSelecting = false;
			startX = startY = endX = endY = 0;
			recognizedText = "Drag to select the area containing text.";
			translatedText = "";
			invalidate();
			// notify activity if it cares
			if (selectionListener != null)
				selectionListener.onSelectionCancelled();
		}

	}

	// Setter for the translation target language tag
	public void setTargetLanguageTag(String tag) {
		if (tag == null || tag.isEmpty() || tag.equals("auto")) {
			// default to English if caller provides "auto" or invalid value
			this.targetLangTag = "en";
		} else {
			this.targetLangTag = tag;
		}
	}

	private void drawInstructionText(Canvas canvas, String text) {
		float x = getWidth() / 2f;
		float y = getHeight() / 2f;
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTextSize(40f);
		textDisplayBounds.set((int) (x - 400), (int) (y - 50), (int) (x + 400), (int) (y + 50));
		// Simple background for text
		textPaint.setColor(Color.argb(200, 0, 0, 0));
		canvas.drawRect(textDisplayBounds, textPaint);
		// Draw text
		textPaint.setColor(Color.WHITE);
		canvas.drawText(text, x, y + 10, textPaint);
	}

	private void drawResultText(Canvas canvas) {
		float centerX = getWidth() / 2f;
		float centerY = getHeight() / 2f;
		float padding = 30f;
		float boxWidth = getWidth() * 0.9f;

		textPaint.setTextSize(30f);
		textPaint.setTextAlign(Paint.Align.LEFT);

		// Simple line wrapping/splitting for display
		String[] lines = translatedText.split("\n");
		float totalTextHeight = lines.length * 40f; // Approx height per line
		float boxHeight = totalTextHeight + padding * 2;

		float boxTop = centerY - boxHeight / 2;
		float boxBottom = centerY + boxHeight / 2;
		float boxLeft = centerX - boxWidth / 2;
		float boxRight = centerX + boxWidth / 2;

		// Draw background box
		Paint boxPaint = new Paint();
		boxPaint.setColor(Color.argb(230, 0, 0, 0)); // Dark semi-transparent
		canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 20f, 20f, boxPaint);

		// Draw the text lines
		textPaint.setColor(Color.WHITE);
		float currentY = boxTop + padding + 30f; // Start Y position

		for (String line : lines) {
			canvas.drawText(line, boxLeft + padding, currentY, textPaint);
			currentY += 40f; // Line spacing
		}
	}

	// --- Touch & Selection Logic ---

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (sourceBitmap == null) {
			return false;
		}

		float x = event.getX();
		float y = event.getY();

		switch (event.getAction()) {
		case MotionEvent.ACTION_DOWN:
			isSelecting = true;
			startX = x;
			startY = y;
			endX = x;
			endY = y;
			recognizedText = "Selecting area...";
			translatedText = "";
			invalidate();
			break;

		case MotionEvent.ACTION_MOVE:
			if (isSelecting) {
				endX = x;
				endY = y;
				invalidate();
			}
			break;

		case MotionEvent.ACTION_UP:
			isSelecting = false;
			endX = x;
			endY = y;

			int left = (int) Math.min(startX, endX);
			int top = (int) Math.min(startY, endY);
			int right = (int) Math.max(startX, endX);
			int bottom = (int) Math.max(startY, endY);

			if (right - left > 50 && bottom - top > 50) {
				performOcr(left, top, right, bottom);
				if (selectionListener != null)
					selectionListener.onSelectionFinished();
			} else {
				Toast.makeText(getContext(), "Selection too small. Exiting OCR Mode.", Toast.LENGTH_SHORT).show();
				// Call public method on activity
				if (getContext() instanceof BrowserActivity) {
					((BrowserActivity) getContext()).toggleOcrMode(false);
				}
			}
			invalidate();
			break;
		}
		return true;
	}

	// --- OCR Trigger ---

	private void performOcr(int left, int top, int right, int bottom) {
		if (sourceBitmap == null || ocrHelper == null) {
			Toast.makeText(getContext(), "System not ready for OCR.", Toast.LENGTH_SHORT).show();
			return;
		}

		try {
			// Ensure crop bounds are within bitmap
			int w = Math.max(1, right - left);
			int h = Math.max(1, bottom - top);
			left = Math.max(0, Math.min(left, sourceBitmap.getWidth() - 1));
			top = Math.max(0, Math.min(top, sourceBitmap.getHeight() - 1));
			if (left + w > sourceBitmap.getWidth())
				w = sourceBitmap.getWidth() - left;
			if (top + h > sourceBitmap.getHeight())
				h = sourceBitmap.getHeight() - top;

			Bitmap croppedBitmap = Bitmap.createBitmap(sourceBitmap, left, top, w, h);

			recognizedText = "Processing OCR...";
			translatedText = "";
			invalidate();

			// Start the asynchronous OCR task
			ocrHelper.recognizeText(croppedBitmap, this);

		} catch (Exception e) {
			recognizedText = "Error during cropping: " + e.getMessage();
			invalidate();
		}
	}

	// --- OcrHelper.OcrCallback Implementation ---

	@Override
	public void onSuccess(String extractedText) {
		this.recognizedText = extractedText.trim();
		if (this.recognizedText.isEmpty()) {
			this.translatedText = "No text recognized.";
			invalidate();
			return;
		}

		// Immediately start translation after successful OCR
		translateText(this.recognizedText);

		// Show loading state for translation
		this.recognizedText = "Translating...";
		invalidate();
	}

	@Override
	public void onError(Exception e) {
		this.recognizedText = "OCR Failed: " + e.getLocalizedMessage();
		this.translatedText = "";
		invalidate();
	}

	// --- Translation using ML Kit on-device translation ---

	private void translateText(@NonNull String text) {
		pendingTranslationText = text;

		post(() -> {
			this.recognizedText = "Detecting language...";
			this.translatedText = "";
			invalidate();
		});

		// 1) Detect language
		langIdentifier.identifyLanguage(text).addOnSuccessListener(languageCode -> {
			if (languageCode == null || languageCode.equals("und")) {
				post(() -> {
					translatedText = "No language detected. Showing original text.";
					recognizedText = text;
					invalidate();
				});
				return;
			}

			// If already English, skip translation
			if (languageCode.equalsIgnoreCase("en")) {
				post(() -> {
					translatedText = "Detected English; no translation needed.";
					recognizedText = text;
					invalidate();
				});
				return;
			}

			// Map to TranslateLanguage constant; fallback if mapping fails
			String sourceLangTag = languageCode.toLowerCase(Locale.US);
			String sourceLang = TranslateLanguage.fromLanguageTag(sourceLangTag);
			if (sourceLang == null) {
				// Try the two-letter code fallback or show original
				post(() -> {
					translatedText = "Translation unavailable for detected language: " + sourceLangTag;
					recognizedText = text;
					invalidate();
				});
				return;
			}

			TranslatorOptions options = new TranslatorOptions.Builder().setSourceLanguage(sourceLang)
					.setTargetLanguage(TranslateLanguage.ENGLISH).build();

			// Close previous translator if any
			if (currentTranslator != null) {
				try {
					currentTranslator.close();
				} catch (Exception ignored) {
				}
				currentTranslator = null;
			}
			currentTranslator = Translation.getClient(options);

			// Model download conditions (adjust as desired)
			DownloadConditions conditions = new DownloadConditions.Builder()
					// remove requireWifi() if you want cellular downloads
					.build();

			post(() -> {
				recognizedText = "Downloading translation model...";
				translatedText = "";
				invalidate();
			});

			currentTranslator.downloadModelIfNeeded(conditions).addOnSuccessListener(aVoid -> {
				post(() -> recognizedText = "Translating...");
				invalidate();
				currentTranslator.translate(text).addOnSuccessListener(translated -> post(() -> {
					translatedText = "Translated:\n" + translated;
					recognizedText = "";
					invalidate();
				})).addOnFailureListener(e -> post(() -> {
					translatedText = "Translation failed: " + e.getLocalizedMessage();
					recognizedText = "";
					invalidate();
				}));
			}).addOnFailureListener(e -> post(() -> {
				translatedText = "Model download failed: " + e.getLocalizedMessage();
				recognizedText = "";
				invalidate();
			}));
		}).addOnFailureListener(e -> post(() -> {
			translatedText = "Language detection failed: " + e.getLocalizedMessage();
			recognizedText = "";
			invalidate();
		}));
	}

	/**
	* Cleanup translation resources. Call from Activity.onDestroy().
	*/
	public void closeTranslationResources() {
		try {
			if (currentTranslator != null) {
				currentTranslator.close();
				currentTranslator = null;
			}
		} catch (Exception ignored) {
		}

		try {
			if (langIdentifier != null) {
				langIdentifier.close();
				langIdentifier = null;
			}
		} catch (Exception ignored) {
		}
	}
}
