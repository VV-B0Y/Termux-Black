package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;

import androidx.drawerlayout.widget.DrawerLayout;

/**
 * A {@link DrawerLayout} tuned for fullscreen drawers. DrawerLayout's built-in drag-to-close uses a
 * very small touch slop, so a slightly-diagonal vertical scroll inside the drawer content (a
 * WebView or ListView) is misread as a close gesture. This subclass only allows a drawer to be
 * dragged closed once the user has made a clearly horizontal swipe (at least 24dp and more
 * horizontal than vertical). Vertical scrolling is never stolen from the drawer content.
 */
public class FullScreenDrawerLayout extends DrawerLayout {

    private float mDownX;
    private float mDownY;
    private boolean mAllowClose;

    public FullScreenDrawerLayout(Context context) {
        super(context);
    }

    public FullScreenDrawerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FullScreenDrawerLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean anyDrawerOpen = isDrawerOpen(Gravity.LEFT) || isDrawerOpen(Gravity.RIGHT);
        if (!anyDrawerOpen) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mAllowClose = false;
                return super.onInterceptTouchEvent(ev);

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                float slop = 24f * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    mAllowClose = true;
                }
                if (!mAllowClose) {
                    // Not a clear horizontal swipe yet: leave the touch to the drawer content so
                    // vertical scrolling (e.g. in the WebView) keeps working.
                    return false;
                }
                return super.onInterceptTouchEvent(ev);
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mAllowClose = false;
                return super.onInterceptTouchEvent(ev);
        }

        return super.onInterceptTouchEvent(ev);
    }
}
