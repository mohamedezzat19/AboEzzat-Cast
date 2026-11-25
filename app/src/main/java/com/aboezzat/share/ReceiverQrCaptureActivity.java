package com.aboezzat.share;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * QR camera screen with a gallery shortcut displayed on the same screen.
 * The selected image URI is returned to MainActivity so the existing,
 * already-tested gallery decoder and pairing validation remain the single path.
 */
public class ReceiverQrCaptureActivity extends CaptureActivity {
    private static final int PICK_QR_IMAGE_REQUEST = 701;
    public static final String EXTRA_GALLERY_IMAGE_URI = "receiver_qr_gallery_uri";

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_receiver_qr_capture);
        Button galleryButton = findViewById(R.id.button_pick_qr_from_gallery);
        galleryButton.setOnClickListener(view -> openGallery());
        return findViewById(R.id.zxing_barcode_scanner);
    }

    private void openGallery() {
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("image/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker, PICK_QR_IMAGE_REQUEST);
        } catch (Exception error) {
            Toast.makeText(this, "تعذر فتح معرض الصور.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_QR_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri imageUri = data.getData();
                Intent result = new Intent();
                result.putExtra(EXTRA_GALLERY_IMAGE_URI, imageUri.toString());
                setResult(RESULT_OK, result);
                finish();
            } else {
                Toast.makeText(this, "تم إلغاء اختيار صورة QR.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
