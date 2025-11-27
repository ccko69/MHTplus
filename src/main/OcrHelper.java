package com.ccko.mhtplus;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OcrHelper: Handles asynchronous text recognition using ML Kit Vision API.
 * This class takes a Bitmap (a cropped screenshot) and returns the extracted text.
 */
public class OcrHelper {

	private static final String TAG = "OcrHelper";
	// We make the recognizer lazy initialized (it's best practice, though ML Kit handles it fine)
	private final TextRecognizer recognizer;

	/**
	 * Callback interface to return results to the calling Activity/Fragment.
	 * This will be implemented by OcrOverlayView.
	 */
	public interface OcrCallback {
		void onSuccess(String extractedText);

		void onError(Exception e);
	}

	public OcrHelper() {
		// Initialize the TextRecognizer for Latin script (English, most European languages).
		recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
	}

	/**
	 * Processes a Bitmap image to extract text asynchronously.
	 *
	 * @param bitmap The image containing the text to be recognized.
	 * @param callback The interface to handle success or failure.
	 */
	public void recognizeText(@NonNull Bitmap bitmap, @NonNull final OcrCallback callback) {
		// Ensure the recognizer is not closed or null
		if (recognizer == null) {
			callback.onError(new IllegalStateException("OCR Recognizer is not initialized."));
			return;
		}

		// 1. Create InputImage object from the Bitmap
		InputImage image = InputImage.fromBitmap(bitmap, 0);

		// 2. Start the asynchronous recognition task
		recognizer.process(image).addOnSuccessListener(result -> {
			// 3. Task successful: Extract and format the recognized text
			String extractedText = formatExtractedText(result);
			callback.onSuccess(extractedText);
		}).addOnFailureListener(e -> {
			// 4. Task failed: Log the error and inform the caller
			Log.e(TAG, "Text recognition failed.", e);
			callback.onError(e);
		});
	}

	/**
	 * Formats the ML Kit result into a clean, single String.
	 *
	 * @param result The Text object returned by ML Kit.
	 * @return A single String containing all recognized text.
	 */
	private String formatExtractedText(Text result) {
		StringBuilder resultText = new StringBuilder();
		for (Text.TextBlock block : result.getTextBlocks()) {
			for (Text.Line line : block.getLines()) {
				// Append text from each line, adding a newline for clean parsing
				resultText.append(line.getText()).append("\n");
			}
		}
		return resultText.toString().trim();
	}

	/**
	 * Optional cleanup method.
	 */
	public void close() {
		// recognizer.close(); // Not required for TextRecognition.getClient(options)
	}
}