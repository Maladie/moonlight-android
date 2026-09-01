package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Fits artwork using source pixels, independent of the TV's logical density. */
public final class ArtworkImageView extends ImageView {
    public ArtworkImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateArtworkMatrix();
    }

    @Override public void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        updateArtworkMatrix();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateArtworkMatrix();
    }

    private void updateArtworkMatrix() {
        Drawable drawable = getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return;
        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
        Bitmap bitmap = bitmapDrawable.getBitmap();
        if (bitmap == null || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) return;
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = LoadingArtworkPolicy.scale(bitmap.getWidth(), bitmap.getHeight(), width, height);
        Matrix matrix = new Matrix();
        matrix.setScale(scale * bitmap.getWidth() / drawable.getIntrinsicWidth(),
                scale * bitmap.getHeight() / drawable.getIntrinsicHeight());
        matrix.postTranslate((width - bitmap.getWidth() * scale) / 2f,
                (height - bitmap.getHeight() * scale) / 2f);
        bitmapDrawable.setFilterBitmap(true);
        setImageMatrix(matrix);
    }
}
