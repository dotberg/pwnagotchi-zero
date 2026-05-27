package com.pwnagotchi.wallpaper;

import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.util.*;

/**
 * Matrix Rain Live Wallpaper — punishment for 6h of driver hell.
 */
public class MatrixWallpaper extends WallpaperService {
    
    @Override
    public Engine onCreateEngine() {
        return new MatrixEngine();
    }
    
    class MatrixEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<RainColumn> columns = new ArrayList<>();
        private final Random rng = new Random();
        private final Paint paint = new Paint();
        private final int CHAR_SIZE_DP = 14;
        private int charSize, width, height, cols;
        private long lastFrame;
        private int frameCount;
        private Bitmap buffer;
        private Canvas bufferCanvas;
        
        // Matrix chars: katakana + digits + hex
        private static final String MATRIX_CHARS = 
            "ｦｧｨｩｪｫｬｭｮｯｱｲｳｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜ" +
            "0123456789ABCDEFabcdef" +
            "01アイウエオカキクケコサシスセソタチツテト";
        
        private class RainColumn {
            float x, y, speed;
            int len, headIndex;
            
            RainColumn(float x) {
                this.x = x;
                reset();
                this.y = rng.nextInt(height);
            }
            
            void reset() {
                y = -rng.nextInt(height * 2);
                speed = 1.5f + rng.nextFloat() * 4f;
                len = 5 + rng.nextInt(25);
                headIndex = rng.nextInt(MATRIX_CHARS.length());
            }
            
            void update() {
                y += speed;
                if (y - len * charSize > height) reset();
                if (rng.nextFloat() < 0.03f) headIndex = rng.nextInt(MATRIX_CHARS.length());
            }
            
            void draw(Canvas c) {
                for (int i = 0; i < len; i++) {
                    float cy = y - i * charSize;
                    if (cy < -charSize || cy > height) continue;
                    
                    float alpha = 1f - (float)i / len;
                    if (i == 0) {
                        // Head — bright white
                        paint.setColor(Color.argb(255, 200, 255, 200));
                    } else if (i < 3) {
                        // Near head — bright green
                        paint.setColor(Color.argb((int)(255 * alpha), 50, 255, 50));
                    } else {
                        // Tail — fading green
                        paint.setColor(Color.argb((int)(100 * alpha * alpha), 0, 180, 0));
                    }
                    
                    char ch = MATRIX_CHARS.charAt((headIndex + i) % MATRIX_CHARS.length());
                    c.drawText(String.valueOf(ch), x, cy, paint);
                }
            }
        }
        
        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            charSize = (int)(CHAR_SIZE_DP * getResources().getDisplayMetrics().density);
            paint.setAntiAlias(true);
            paint.setTextSize(charSize);
            paint.setTypeface(Typeface.MONOSPACE);
            setTouchEventsEnabled(false);
        }
        
        @Override
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                lastFrame = System.currentTimeMillis();
                handler.post(drawRunnable);
            } else {
                handler.removeCallbacks(drawRunnable);
            }
        }
        
        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            cols = width / charSize + 2;
            
            buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bufferCanvas = new Canvas(buffer);
            
            columns.clear();
            for (int i = 0; i < cols; i++) {
                columns.add(new RainColumn(i * charSize));
            }
        }
        
        private final Runnable drawRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isVisible()) return;
                
                frameCount++;
                long now = System.currentTimeMillis();
                long delta = now - lastFrame;
                lastFrame = now;
                
                // Dim background for trail effect
                bufferCanvas.drawColor(Color.argb(15, 0, 0, 0));
                
                for (RainColumn col : columns) {
                    col.update();
                    col.draw(bufferCanvas);
                }
                
                // FPS counter in corner (debug)
                if (frameCount % 30 == 0) {
                    // skip for perf
                }
                
                // Draw synthwave sun + grid every 60 frames
                if (frameCount % 120 == 0) {
                    bufferCanvas.drawColor(Color.argb(5, 0, 0, 0));
                }
                
                SurfaceHolder holder = getSurfaceHolder();
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if (c != null) {
                        c.drawBitmap(buffer, 0, 0, null);
                    }
                } finally {
                    if (c != null) holder.unlockCanvasAndPost(c);
                }
                
                handler.postDelayed(this, 50); // ~20 FPS
            }
        };
        
        @Override
        public void onDestroy() {
            handler.removeCallbacks(drawRunnable);
            if (buffer != null) buffer.recycle();
            super.onDestroy();
        }
    }
}
