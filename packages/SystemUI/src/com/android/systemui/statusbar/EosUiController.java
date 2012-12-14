
package com.android.systemui.statusbar;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.policy.KeyButtonView;

import org.teameos.jellybean.settings.EOSConstants;
import org.teameos.jellybean.settings.ActionHandler;

import java.util.ArrayList;
import java.util.Arrays;

public class EosUiController extends ActionHandler {
    static final String TAG = "NavigationAreaController";

    static final int STOCK_NAV_BAR = com.android.systemui.R.layout.navigation_bar;
    static final int EOS_NAV_BAR = com.android.systemui.R.layout.eos_navigation_bar;
    static final int BACK_KEY = com.android.systemui.R.id.back;
    static final int HOME_KEY = com.android.systemui.R.id.home;
    static final int RECENT_KEY = com.android.systemui.R.id.recent_apps;
    static final int MENU_KEY = com.android.systemui.R.id.menu;

    private ArrayList<View> mBatteryList = new ArrayList<View>();

    // we'll cheat just a little to help with the two navigation bar views
    static final int NAVBAR_ROT_90 = com.android.systemui.R.id.rot90;
    static final int NAVBAR_ROT_0 = com.android.systemui.R.id.rot0;

    static final String BACK_KEY_URI_TAG = EOSConstants.SYSTEMUI_SOFTKEY_BACK;
    static final String HOME_KEY_URI_TAG = EOSConstants.SYSTEMUI_SOFTKEY_HOME;
    static final String RECENT_KEY_URI_TAG = EOSConstants.SYSTEMUI_SOFTKEY_RECENT;
    static final String MENU_KEY_URI_TAG = EOSConstants.SYSTEMUI_SOFTKEY_MENU;
    static final int BACK_KEY_LOCATION = 0;
    static final int HOME_KEY_LOCATION = 1;
    static final int RECENT_KEY_LOCATION = 2;
    static final int MENU_KEY_LOCATION = 3;

    private static boolean DEBUG = false;

    private ArrayList<SoftKeyObject> mSoftKeyObjects;

    private Context mContext;
    private PhoneStatusBarView mStatusBarView;
    private PhoneStatusBar mService;
    private View mStatusBarContainer;
    private NavigationBarView mNavigationBarView;
    private ContentResolver mResolver;
    private SettingsObserver mObserver;
    private ContentObserver mHideBarObserver;
    private ContentObserver mBatterySettingsObserver;
    private IWindowManager wm;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mNavigationBarParams;
    private WindowManager.LayoutParams mStatusBarParams;
    private boolean mHasNavBar = false;

    public EosUiController(Context context) {
        super(context);
        mContext = context;
        mResolver = mContext.getContentResolver();
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        wm = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        try {
            mHasNavBar = wm.hasNavigationBar();
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // no matter what, we set make bar visibility true on boot
        Settings.System.putInt(mResolver, EOSConstants.SYSTEMUI_HIDE_BARS,
                EOSConstants.SYSTEMUI_HIDE_BARS_DEF);

        mHideBarObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateBarVisibility();
            }
        };     

        mBatterySettingsObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                processBatterySettingsChange();
            }
        };
        
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_BATTERY_ICON_VISIBLE), false,
                mBatterySettingsObserver);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_BATTERY_TEXT_VISIBLE), false,
                mBatterySettingsObserver);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_BATTERY_TEXT_COLOR), false,
                mBatterySettingsObserver);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_BATTERY_PERCENT_VISIBLE), false,
                mBatterySettingsObserver);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_HIDE_BARS), false,
                mHideBarObserver);
    }

    public NavigationBarView setNavigationBarView(WindowManager.LayoutParams lp) {
        mNavigationBarParams = lp;
        int layout = Settings.System.getInt(mResolver,
                EOSConstants.SYSTEMUI_USE_HYBRID_STATBAR,
                EOSConstants.SYSTEMUI_USE_HYBRID_STATBAR_DEF)
                == EOSConstants.SYSTEMUI_USE_HYBRID_STATBAR_DEF
                        ? STOCK_NAV_BAR
                        : EOS_NAV_BAR;
        mNavigationBarView = (NavigationBarView) View.inflate(mContext, layout, null);
        startNavigationBarFeatures();
        return mNavigationBarView;
    }

    private void startNavigationBarFeatures() {
        // register here instead. Why register in constructor if device
        // does not have systembar/navbar ie crespo
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_NAVBAR_COLOR), false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateNavigationBarColor();
                    }
                });

        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_USE_HYBRID_STATBAR), false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        processBarStyleChange();
                    }
                });

        mSoftKeyObjects = new ArrayList<SoftKeyObject>();
        mObserver = new SettingsObserver(H);

        initSoftKeys();
    }

    public void setBar(PhoneStatusBar service) {
        mService = service;
    }

    // we need this to be set when the theme engine creates new view
    public void setStatusBarView(PhoneStatusBarView bar) {
        mStatusBarView = bar;

        // only register if device has statusbar
        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_STATUSBAR_COLOR), false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateStatusBarColor();
                    }
                });
        updateStatusBarColor();

        // now we're sure we're getting the correct batteries
        View text = mStatusBarView
                .findViewById(R.id.signal_battery_cluster)
                .findViewById(R.id.battery_text);
        text.setTag(EOSConstants.SYSTEMUI_BATTERY_PERCENT_TAG);
        mBatteryList.add(text);

        mBatteryList.add(mStatusBarView
                .findViewById(R.id.signal_battery_cluster)
                .findViewById(R.id.battery));
        processBatterySettingsChange();
    }

    // we need this to add and remove the view from the windowmanager
    public void setStatusBarContainer(View container, WindowManager.LayoutParams lp) {
        mStatusBarContainer = container;
        mStatusBarParams = lp;
    }

    static void log(String s) {
        if (DEBUG)
            Log.i(TAG, s);
    }

    void loadBackKey(ArrayList<View> parent) {
        SoftKeyObject back = new SoftKeyObject();
        back.setSoftKey(BACK_KEY,
                BACK_KEY_LOCATION,
                BACK_KEY_URI_TAG,
                new SoftkeyLongClickListener(BACK_KEY_LOCATION),
                parent);
        mSoftKeyObjects.add(back);
    }

    void loadHomeKey(ArrayList<View> parent) {
        SoftKeyObject home = new SoftKeyObject();
        home.setSoftKey(HOME_KEY,
                HOME_KEY_LOCATION,
                HOME_KEY_URI_TAG,
                new SoftkeyLongClickListener(HOME_KEY_LOCATION),
                parent);
        mSoftKeyObjects.add(home);
    }

    void loadRecentKey(ArrayList<View> parent) {
        SoftKeyObject recent = new SoftKeyObject();
        recent.setSoftKey(RECENT_KEY,
                RECENT_KEY_LOCATION,
                RECENT_KEY_URI_TAG,
                new SoftkeyLongClickListener(RECENT_KEY_LOCATION),
                parent);
        mSoftKeyObjects.add(recent);
    }

    void loadMenuKey(ArrayList<View> parent) {
        SoftKeyObject menu = new SoftKeyObject();
        menu.setSoftKey(MENU_KEY,
                MENU_KEY_LOCATION,
                MENU_KEY_URI_TAG,
                new SoftkeyLongClickListener(MENU_KEY_LOCATION),
                parent);
        mSoftKeyObjects.add(menu);
    }

    public void loadActions() {
        if (mActions == null)
            mActions = new ArrayList<String>();
        else
            mActions.clear();
        String[] actions = new String[4];
        for (SoftKeyObject s : mSoftKeyObjects) {
            actions[s.mPosition] = Settings.System.getString(mResolver, s.mUri);
            mResolver.registerContentObserver(Settings.System.getUriFor(s.mUri), false,
                    mObserver);
            s.loadListener();
        }
        mActions.addAll(Arrays.asList(actions));
    }

    public void unloadActions() {
        mResolver.unregisterContentObserver(mObserver);
        for (SoftKeyObject s : mSoftKeyObjects) {
            s.unloadListener();
        }
    }

    void initSoftKeys() {
        // softkey objects only need to the the parent view
        ArrayList<View> parent = new ArrayList<View>();
            parent.add(mNavigationBarView.findViewById(NAVBAR_ROT_90));
            parent.add(mNavigationBarView.findViewById(NAVBAR_ROT_0));
        loadBackKey(parent);
//        loadHomeKey(parent);
        loadRecentKey(parent);
        loadMenuKey(parent);

        mResolver.registerContentObserver(
                Settings.System.getUriFor(EOSConstants.SYSTEMUI_NAVBAR_DISABLE_GESTURE), false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateNavigationLongPressState();
                    }
                });
        updateNavigationLongPressState();
    }

    private void updateNavigationLongPressState() {
        if (Settings.System.getInt(mResolver,
                EOSConstants.SYSTEMUI_NAVBAR_DISABLE_GESTURE,
                EOSConstants.SYSTEMUI_NAVBAR_DISABLE_GESTURE_DEF) == 1) {
            loadActions();
        } else {
            unloadActions();
        }
    }

    private void processBarStyleChange() {
        // time to die, but i shall return again soon
        System.exit(0);
    }

    public Handler getHandler() {
        return H;
    }

    private Handler H = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {

            }
        }
    };

    private class SettingsObserver extends ContentObserver {
        Handler mHandler;

        public SettingsObserver(Handler handler) {
            super(handler);
            mHandler = handler;
        }

        public void onChange(boolean selfChange) {
            loadActions();
        }
    }

    @Override
    public boolean handleAction(String action) {
        return true;
    }

    class SoftkeyLongClickListener implements View.OnLongClickListener {
        int position;

        public SoftkeyLongClickListener(int i) {
            position = i;
        }

        @Override
        public boolean onLongClick(View v) {
            // TODO Auto-generated method stub
            handleEvent(position);
            return false;
        }

    }

    // Dummy cheaters class to help keep organized
    protected static class SoftKeyObject {
        private int mId;
        private ArrayList<View> mParent = new ArrayList<View>();
        private int mPosition;
        private String mUri;
        private SoftkeyLongClickListener mListener;
        private int mKeyCode;
        private boolean mSupportsLongPress = true;

        public void setSoftKey(int id, int position, String uri,
                SoftkeyLongClickListener l, ArrayList<View> parent) {
            mId = id;
            mPosition = position;
            mUri = uri;
            mListener = l;
            mParent = parent;
        }

        public void loadListener() {
            for (View v : mParent) {
                KeyButtonView key = (KeyButtonView) v.findViewById(mId);
                key.setOnLongClickListener(null);
                key.setOnLongClickListener(mListener);
                key.disableLongPressIntercept(true);
                mSupportsLongPress = key.getSupportsLongPress();
                if (!mSupportsLongPress) key.setSupportsLongPress(true);                
            }
        }

        public void unloadListener() {
            for (View v : mParent) {
                KeyButtonView key = (KeyButtonView) v.findViewById(mId);
                key.setOnLongClickListener(null);
                key.disableLongPressIntercept(false);
                if (key.getSupportsLongPress()) key.setSupportsLongPress(false);
            }
        }

        public void dump() {
            StringBuilder b = new StringBuilder();
            b.append("Id = " + String.valueOf(mId))
                    .append(" Postition = " + String.valueOf(mPosition))
                    .append(" Uri = " + mUri);
            log(b.toString());
        }
    }

    // applies to Navigation Bar or Systembar
    private void updateNavigationBarColor() {
        int color = Settings.System.getInt(mContext.getContentResolver(),
                EOSConstants.SYSTEMUI_NAVBAR_COLOR,
                EOSConstants.SYSTEMUI_NAVBAR_COLOR_DEF);
        if (color == -1)
            color = EOSConstants.SYSTEMUI_NAVBAR_COLOR_DEF;
        // we don't want alpha here
        color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
        mNavigationBarView.setBackgroundColor(color);
    }    

    // applies to Statusbar only 
    private void updateStatusBarColor() {
        int color = Settings.System.getInt(mContext.getContentResolver(),
                EOSConstants.SYSTEMUI_STATUSBAR_COLOR,
                EOSConstants.SYSTEMUI_STATUSBAR_COLOR_DEF);
        if (color == -1)
            color = EOSConstants.SYSTEMUI_NAVBAR_COLOR_DEF;
        // we don't want alpha here
        color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
        mStatusBarView.setBackgroundColor(color);
    }

    private void updateBarVisibility() {
        boolean hideBar = (Settings.System.getInt(mResolver, EOSConstants.SYSTEMUI_HIDE_BARS,
                EOSConstants.SYSTEMUI_HIDE_BARS_DEF) == 1) ? true : false;
        // all devices have a status bar but may not have a navbar
        if (hideBar) {
            mWindowManager.removeView(mStatusBarContainer);
        } else {
            mWindowManager.addView(mStatusBarContainer, mStatusBarParams);
        }
        if (mHasNavBar) {
            if (hideBar) {
                mWindowManager.removeView(mNavigationBarView);
            } else {
                mWindowManager.addView(mNavigationBarView, mNavigationBarParams);
            }
        }
    }

    private void processBatterySettingsChange() {
        int icon_visible = (Settings.System.getInt(mContext.getContentResolver(),
                EOSConstants.SYSTEMUI_BATTERY_ICON_VISIBLE,
                EOSConstants.SYSTEMUI_BATTERY_ICON_VISIBLE_DEF) == 1) ? View.VISIBLE : View.GONE;

        int text_visible = (Settings.System.getInt(mContext.getContentResolver(),
                EOSConstants.SYSTEMUI_BATTERY_TEXT_VISIBLE,
                EOSConstants.SYSTEMUI_BATTERY_TEXT_VISIBLE_DEF) == 1) ? View.VISIBLE : View.GONE;

        int color = Settings.System.getInt(mContext.getContentResolver(),
                EOSConstants.SYSTEMUI_BATTERY_TEXT_COLOR,
                EOSConstants.SYSTEMUI_BATTERY_TEXT_COLOR_DEF);
        if (color == -1) {
            color = mContext.getResources()
                    .getColor(android.R.color.holo_blue_light);
        }
        for (View v : mBatteryList) {
            if (v.getTag() != null
                    && v.getTag().equals(EOSConstants.SYSTEMUI_BATTERY_PERCENT_TAG)) {
                // this is our text view
                ((TextView)v).setTextColor(color);
                v.setVisibility(text_visible);
            } else {
                // this works for now as we are only controlling
                // two views at any time
                v.setVisibility(icon_visible);
            }
        }
    }

    // utility to help bigclearbutton feature
    // seems ok here for now as it could be useful later
    public static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }
}
