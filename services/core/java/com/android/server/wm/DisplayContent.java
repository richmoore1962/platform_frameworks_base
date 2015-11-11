/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static com.android.server.wm.WindowManagerService.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerService.TAG;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;
import static com.android.server.wm.WindowState.BOUNDS_FOR_TOUCH;

import android.app.ActivityManager.StackId;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.ArrayList;

class DisplayContentList extends ArrayList<DisplayContent> {
}

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent {

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    /** Z-ordered (bottom-most first) list of all Window objects. Assigned to an element
     * from mDisplayWindows; */
    private final WindowList mWindows = new WindowList();

    // This protects the following display size properties, so that
    // getDisplaySize() doesn't need to acquire the global lock.  This is
    // needed because the window manager sometimes needs to use ActivityThread
    // while it has its global state locked (for example to load animation
    // resources), but the ActivityThread also needs get the current display
    // size sometimes when it has its package lock held.
    //
    // These will only be modified with both mWindowMap and mDisplaySizeLock
    // held (in that order) so the window manager doesn't need to acquire this
    // lock when needing these values in its normal operation.
    final Object mDisplaySizeLock = new Object();
    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mInitialDisplayDensity = 0;
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    int mBaseDisplayDensity = 0;
    boolean mDisplayScalingDisabled;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    Rect mBaseDisplayRect = new Rect();
    Rect mContentRect = new Rect();

    // Accessed directly by all users.
    boolean layoutNeeded;
    int pendingLayoutChanges;
    final boolean isDefaultDisplay;

    /** Window tokens that are in the process of exiting, but still on screen for animations. */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<>();

    /** Array containing all TaskStacks on this display.  Array
     * is stored in display order with the current bottom stack at 0. */
    private final ArrayList<TaskStack> mStacks = new ArrayList<>();

    /** A special TaskStack with id==HOME_STACK_ID that moves to the bottom whenever any TaskStack
     * (except a future lockscreen TaskStack) moves to the top. */
    private TaskStack mHomeStack = null;

    /** Detect user tapping outside of current focused task bounds .*/
    TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    Region mTouchExcludeRegion = new Region();

    /** Save allocating when calculating rects */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();

    /** For gathering Task objects in order. */
    final ArrayList<Task> mTmpTaskHistory = new ArrayList<Task>();

    final WindowManagerService mService;

    /** Remove this display when animation on it has completed. */
    boolean mDeferredRemoval;

    final DockedStackDividerController mDividerControllerLocked;

    final DimLayerController mDimLayerController;

    /**
     * @param display May not be null.
     * @param service You know.
     */
    DisplayContent(Display display, WindowManagerService service) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        isDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
        mService = service;
        initializeDisplayBaseInfo();
        mDividerControllerLocked = new DockedStackDividerController(service.mContext, this);
        mDimLayerController = new DimLayerController(this);
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowList getWindowList() {
        return mWindows;
    }

    Display getDisplay() {
        return mDisplay;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    DisplayMetrics getDisplayMetrics() {
        return mDisplayMetrics;
    }

    DockedStackDividerController getDockedDividerController() {
        return mDividerControllerLocked;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    public boolean hasAccess(int uid) {
        return mDisplay.hasAccess(uid);
    }

    public boolean isPrivate() {
        return (mDisplay.getFlags() & Display.FLAG_PRIVATE) != 0;
    }

    ArrayList<TaskStack> getStacks() {
        return mStacks;
    }

    /**
     * Retrieve the tasks on this display in stack order from the bottommost TaskStack up.
     * @return All the Tasks, in order, on this display.
     */
    ArrayList<Task> getTasks() {
        mTmpTaskHistory.clear();
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            mTmpTaskHistory.addAll(mStacks.get(stackNdx).getTasks());
        }
        return mTmpTaskHistory;
    }

    TaskStack getHomeStack() {
        if (mHomeStack == null && mDisplayId == Display.DEFAULT_DISPLAY) {
            Slog.e(TAG, "getHomeStack: Returning null from this=" + this);
        }
        return mHomeStack;
    }

    void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).updateDisplayInfo(null);
        }
    }

    void initializeDisplayBaseInfo() {
        synchronized(mDisplaySizeLock) {
            // Bootstrap the default logical display from the display manager.
            final DisplayInfo newDisplayInfo =
                    mService.mDisplayManagerInternal.getDisplayInfo(mDisplayId);
            if (newDisplayInfo != null) {
                mDisplayInfo.copyFrom(newDisplayInfo);
            }
            mBaseDisplayWidth = mInitialDisplayWidth = mDisplayInfo.logicalWidth;
            mBaseDisplayHeight = mInitialDisplayHeight = mDisplayInfo.logicalHeight;
            mBaseDisplayDensity = mInitialDisplayDensity = mDisplayInfo.logicalDensityDpi;
            mBaseDisplayRect.set(0, 0, mBaseDisplayWidth, mBaseDisplayHeight);
        }
    }

    void getLogicalDisplayRect(Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int orientation = mDisplayInfo.rotation;
        boolean rotated = (orientation == Surface.ROTATION_90
                || orientation == Surface.ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    /** Refer to {@link WindowManagerService#attachStack(int, int, boolean)} */
    void attachStack(TaskStack stack, boolean onTop) {
        if (stack.mStackId == HOME_STACK_ID) {
            if (mHomeStack != null) {
                throw new IllegalArgumentException("attachStack: HOME_STACK_ID (0) not first.");
            }
            mHomeStack = stack;
        }
        if (onTop) {
            mStacks.add(stack);
        } else {
            mStacks.add(0, stack);
        }
        layoutNeeded = true;
    }

    void moveStack(TaskStack stack, boolean toTop) {
        if (stack.mStackId == PINNED_STACK_ID && !toTop) {
            // Pinned stack is always-on-top silly...
            Slog.w(TAG, "Ignoring move of always-on-top stack=" + stack + " to bottom");
            return;
        }

        if (!mStacks.remove(stack)) {
            Slog.wtf(TAG, "moving stack that was not added: " + stack, new Throwable());
        }

        int addIndex = toTop ? mStacks.size() : 0;

        if (toTop
                && mService.isStackVisibleLocked(PINNED_STACK_ID)
                && stack.mStackId != PINNED_STACK_ID) {
            // The pinned stack is always the top most stack (always-on-top) when it is visible.
            // So, stack is moved just below the pinned stack.
            addIndex--;
            TaskStack topStack = mStacks.get(addIndex);
            if (topStack.mStackId != PINNED_STACK_ID) {
                throw new IllegalStateException("Pinned stack isn't top stack??? " + mStacks);
            }
        }
        mStacks.add(addIndex, stack);
    }

    void detachStack(TaskStack stack) {
        mDimLayerController.removeDimLayerUser(stack);
        mStacks.remove(stack);
    }

    /**
     * Propagate the new bounds to all child stacks.
     * @param contentRect The bounds to apply at the top level.
     */
    void resize(Rect contentRect) {
        mContentRect.set(contentRect);
    }

    int taskIdFromPoint(int x, int y) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                // We need to use the visible frame on the window for any touch-related tests.
                // Can't use the task's bounds because the original task bounds might be adjusted
                // to fit the content frame. For example, the presence of the IME adjusting the
                // windows frames when the app window is the IME target.
                final WindowState win = task.getTopAppMainWindow();
                if (win != null) {
                    win.getVisibleBounds(mTmpRect);
                    if (mTmpRect.contains(x, y)) {
                        return task.mTaskId;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Find the window whose outside touch area (for resizing) (x, y) falls within.
     * Returns null if the touch doesn't fall into a resizing area.
     */
    WindowState findWindowForControlPoint(int x, int y) {
        final int delta = mService.dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            TaskStack stack = mStacks.get(stackNdx);
            if (!StackId.isTaskResizeAllowed(stack.mStackId)) {
                break;
            }
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                if (task.isFullscreen()) {
                    return null;
                }

                // We need to use the visible frame on the window for any touch-related
                // tests. Can't use the task's bounds because the original task bounds
                // might be adjusted to fit the content frame. (One example is when the
                // task is put to top-left quadrant, the actual visible frame would not
                // start at (0,0) after it's adjusted for the status bar.)
                final WindowState win = task.getTopAppMainWindow();
                if (win != null) {
                    win.getVisibleBounds(mTmpRect);
                    mTmpRect.inset(-delta, -delta);
                    if (mTmpRect.contains(x, y)) {
                        mTmpRect.inset(delta, delta);
                        if (!mTmpRect.contains(x, y)) {
                            return win;
                        }
                        // User touched inside the task. No need to look further,
                        // focus transfer will be handled in ACTION_UP.
                        return null;
                    }
                }
            }
        }
        return null;
    }

    void setTouchExcludeRegion(Task focusedTask) {
        mTouchExcludeRegion.set(mBaseDisplayRect);
        WindowList windows = getWindowList();
        final int delta = mService.dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        for (int i = windows.size() - 1; i >= 0; --i) {
            final WindowState win = windows.get(i);
            final Task task = win.getTask();
            if (win.isVisibleLw() && task != null) {
                /**
                 * Exclusion region is the region that TapDetector doesn't care about.
                 * Here we want to remove all non-focused tasks from the exclusion region.
                 * We also remove the outside touch area for resizing for all freeform
                 * tasks (including the focused).
                 *
                 * (For freeform focused task, the below logic will first remove the enlarged
                 * area, then add back the inner area.)
                 */
                final boolean isFreeformed = task.inFreeformWorkspace();
                if (task != focusedTask || isFreeformed) {
                    mTmpRect.set(win.mVisibleFrame);
                    mTmpRect.intersect(win.mVisibleInsets);
                    /**
                     * If the task is freeformed, enlarge the area to account for outside
                     * touch area for resize.
                     */
                    if (isFreeformed) {
                        mTmpRect.inset(-delta, -delta);
                        // Intersect with display content rect. If we have system decor (status bar/
                        // navigation bar), we want to exclude that from the tap detection.
                        // Otherwise, if the app is partially placed under some system button (eg.
                        // Recents, Home), pressing that button would cause a full series of
                        // unwanted transfer focus/resume/pause, before we could go home.
                        mTmpRect.intersect(mContentRect);
                    }
                    mTouchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
                }
                /**
                 * If we removed the focused task above, add it back and only leave its
                 * outside touch area in the exclusion. TapDectector is not interested in
                 * any touch inside the focused task itself.
                 */
                if (task == focusedTask && isFreeformed) {
                    mTmpRect.inset(delta, delta);
                    mTouchExcludeRegion.op(mTmpRect, Region.Op.UNION);
                }
            }
        }
        if (mTapDetector != null) {
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
        }
    }

    void switchUserStacks() {
        final WindowList windows = getWindowList();
        for (int i = 0; i < windows.size(); i++) {
            final WindowState win = windows.get(i);
            if (win.isHiddenFromUserLocked()) {
                if (DEBUG_VISIBILITY) Slog.w(TAG, "user changing, hiding " + win
                        + ", attrs=" + win.mAttrs.type + ", belonging to " + win.mOwnerUid);
                win.hideLw(false);
            }
        }

        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).switchUser();
        }
    }

    void resetAnimationBackgroundAnimator() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).resetAnimationBackgroundAnimator();
        }
    }

    boolean animateDimLayers() {
        return mDimLayerController.animateDimLayers();
    }

    void resetDimming() {
        mDimLayerController.resetDimming();
    }

    boolean isDimming() {
        return mDimLayerController.isDimming();
    }

    void stopDimmingIfNeeded() {
        mDimLayerController.stopDimmingIfNeeded();
    }

    void close() {
        mDimLayerController.close();
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).close();
        }
    }

    boolean isAnimating() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                return true;
            }
        }
        return false;
    }

    void checkForDeferredActions() {
        boolean animating = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                animating = true;
            } else {
                if (stack.mDeferDetach) {
                    mService.detachStackLocked(this, stack);
                }
                final ArrayList<Task> tasks = stack.getTasks();
                for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                    final Task task = tasks.get(taskNdx);
                    AppTokenList tokens = task.mAppTokens;
                    for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                        AppWindowToken wtoken = tokens.get(tokenNdx);
                        if (wtoken.mIsExiting) {
                            wtoken.removeAppFromTaskLocked();
                        }
                    }
                }
            }
        }
        if (!animating && mDeferredRemoval) {
            mService.onDisplayRemoved(mDisplayId);
        }
    }

    void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        final int rotationDelta = DisplayContent.deltaRotation(oldRotation, newRotation);
        getLogicalDisplayRect(mTmpRect);
        switch (rotationDelta) {
            case Surface.ROTATION_0:
                mTmpRect2.set(bounds);
                break;
            case Surface.ROTATION_90:
                mTmpRect2.top = mTmpRect.bottom - bounds.right;
                mTmpRect2.left = bounds.top;
                mTmpRect2.right = mTmpRect2.left + bounds.height();
                mTmpRect2.bottom = mTmpRect2.top + bounds.width();
                break;
            case Surface.ROTATION_180:
                mTmpRect2.top = mTmpRect.bottom - bounds.bottom;
                mTmpRect2.left = mTmpRect.right - bounds.right;
                mTmpRect2.right = mTmpRect2.left + bounds.width();
                mTmpRect2.bottom = mTmpRect2.top + bounds.height();
                break;
            case Surface.ROTATION_270:
                mTmpRect2.top = bounds.left;
                mTmpRect2.left = mTmpRect.right - bounds.bottom;
                mTmpRect2.right = mTmpRect2.left + bounds.height();
                mTmpRect2.bottom = mTmpRect2.top + bounds.width();
                break;
        }
        bounds.set(mTmpRect2);
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("Display: mDisplayId="); pw.println(mDisplayId);
        final String subPrefix = "  " + prefix;
        pw.print(subPrefix); pw.print("init="); pw.print(mInitialDisplayWidth); pw.print("x");
            pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
            pw.print("dpi");
            if (mInitialDisplayWidth != mBaseDisplayWidth
                    || mInitialDisplayHeight != mBaseDisplayHeight
                    || mInitialDisplayDensity != mBaseDisplayDensity) {
                pw.print(" base=");
                pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
                pw.print(" "); pw.print(mBaseDisplayDensity); pw.print("dpi");
            }
            if (mDisplayScalingDisabled) {
                pw.println(" noscale");
            }
            pw.print(" cur=");
            pw.print(mDisplayInfo.logicalWidth);
            pw.print("x"); pw.print(mDisplayInfo.logicalHeight);
            pw.print(" app=");
            pw.print(mDisplayInfo.appWidth);
            pw.print("x"); pw.print(mDisplayInfo.appHeight);
            pw.print(" rng="); pw.print(mDisplayInfo.smallestNominalAppWidth);
            pw.print("x"); pw.print(mDisplayInfo.smallestNominalAppHeight);
            pw.print("-"); pw.print(mDisplayInfo.largestNominalAppWidth);
            pw.print("x"); pw.println(mDisplayInfo.largestNominalAppHeight);
            pw.print(subPrefix); pw.print("deferred="); pw.print(mDeferredRemoval);
                pw.print(" layoutNeeded="); pw.println(layoutNeeded);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            pw.print(prefix); pw.print("mStacks[" + stackNdx + "]"); pw.println(stack.mStackId);
            stack.dump(prefix + "  ", pw);
        }
        pw.println();
        pw.println("  Application tokens in top down Z order:");
        int ndx = 0;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            pw.print("  mStackId="); pw.println(stack.mStackId);
            ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                pw.print("    mTaskId="); pw.println(task.mTaskId);
                AppTokenList tokens = task.mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx, ++ndx) {
                    final AppWindowToken wtoken = tokens.get(tokenNdx);
                    pw.print("    Activity #"); pw.print(tokenNdx);
                            pw.print(' '); pw.print(wtoken); pw.println(":");
                    wtoken.dump(pw, "      ");
                }
            }
        }
        if (ndx == 0) {
            pw.println("    None");
        }
        pw.println();
        if (!mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i=mExitingTokens.size()-1; i>=0; i--) {
                WindowToken token = mExitingTokens.get(i);
                pw.print("  Exiting #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
        pw.println();
        mDimLayerController.dump(prefix + "  ", pw);
    }

    @Override
    public String toString() {
        return "Display " + mDisplayId + " info=" + mDisplayInfo + " stacks=" + mStacks;
    }

    TaskStack getDockedStackLocked() {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        return (stack != null && stack.isVisibleLocked()) ? stack : null;
    }
}
