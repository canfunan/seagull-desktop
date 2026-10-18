package com.seagull.carlauncher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * 轻量横向分页容器 —— 复刻 Vivo 智能车载 CarLauncher 的 CustomViewPager。
 *
 * 本工程没有 androidx / support-v4 依赖（构建只挂 android.jar），所以自己实现：
 *   · 三页等宽横排，手指拖动跟随，松手按速度/距离吸附到最近页；
 *   · 只在「横向位移超过 touchSlop 且明显大于纵向」时才拦截 —— 点按不受影响，
 *     仍原样落进当前页（中点画中画画布时不会误翻页，见 PipBoard 的单焦点规则）；
 *   · 翻页回调交给宿主同步底部 Dock 高亮。
 *
 * 只做小尺寸横排分页，不含循环/预加载/离屏销毁这些用不到的能力。
 */
public class PagerView extends ViewGroup {

    public interface OnPageChanged {
        void onPageChanged(int index);
    }

    private final Scroller scroller;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;

    private int page = 0;
    private OnPageChanged cb;

    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging;

    public PagerView(Context c) {
        super(c);
        scroller = new Scroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setClipChildren(true);
    }

    public void setOnPageChanged(OnPageChanged cb) { this.cb = cb; }

    public int getCurrentItem() { return page; }

    public int getCount() { return getChildCount(); }

    @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        final int n = getChildCount();
        for (int i = 0; i < n; i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        final int n = getChildCount();
        for (int i = 0; i < n; i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
        // 首帧 w=0 时 setCurrentItem 只能记住页码，落位得等这一次布局。
        if (w > 0) {
            int target = clamp(page) * w;
            if (getScrollX() != target) scrollTo(target, 0);
        }
    }

    /** 切页。animate=false 直接落位（首帧用，避免闪一下）。 */
    public void setCurrentItem(int index, boolean animate) {
        int target = clamp(index);
        int w = getWidth();
        if (w <= 0) { page = target; notifyPage(); return; }
        if (animate) smoothScrollTo(target * w);
        else { scroller.abortAnimation(); scrollTo(target * w, 0); }
        page = target;
        notifyPage();
    }

    private void notifyPage() {
        if (cb != null) cb.onPageChanged(page);
    }

    private int clamp(int index) {
        int max = Math.max(0, getChildCount() - 1);
        if (index < 0) return 0;
        if (index > max) return max;
        return index;
    }

    private void smoothScrollTo(int x) {
        scroller.startScroll(getScrollX(), 0, x - getScrollX(), 0, 320);
        postInvalidateOnAnimation();
    }

    private void clampScroll() {
        int max = Math.max(0, getChildCount() - 1) * Math.max(0, getWidth());
        int x = getScrollX();
        if (x < 0) scrollTo(0, 0);
        else if (x > max) scrollTo(max, 0);
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    private boolean wantHorizontal(MotionEvent e) {
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        return Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f;
    }

    /** 单应用模式锁翻页用：false 时完全不吃横向手势，点按照常落给子视图。 */
    private boolean swipeEnabled = true;

    public void setSwipeEnabled(boolean on) {
        if (swipeEnabled == on) return;
        swipeEnabled = on;
        dragging = false;
        if (vt != null) { vt.recycle(); vt = null; }
        if (!scroller.isFinished()) scroller.abortAnimation();
    }

    public boolean isSwipeEnabled() { return swipeEnabled; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = false;
                if (vt == null) vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (!scroller.isFinished()) scroller.abortAnimation();
                break;
            case MotionEvent.ACTION_MOVE:
                if (swipeEnabled && !dragging && wantHorizontal(e)) {
                    dragging = true;
                    lastX = e.getX();
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.abortAnimation();
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging && swipeEnabled && wantHorizontal(e)) dragging = true;
                if (dragging) {
                    float dx = e.getX() - lastX;
                    scrollBy((int) -dx, 0);
                    clampScroll();
                    lastX = e.getX();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int target = page;
                if (dragging) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vx = vt.getXVelocity();
                    if (Math.abs(vx) > minFling) target = vx < 0 ? page + 1 : page - 1;
                    else {
                        int w = Math.max(1, getWidth());
                        target = Math.round((float) getScrollX() / w);
                    }
                    target = clamp(target);
                }
                finishTouch(target);
                return true;
            }
            default:
                return true;
        }
    }

    private void finishTouch(int target) {
        dragging = false;
        if (vt != null) { vt.recycle(); vt = null; }
        int w = getWidth();
        if (w > 0) smoothScrollTo(target * w);
        if (target != page) { page = target; notifyPage(); }
    }
}
