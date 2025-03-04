package cgeo.geocaching;

import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.list.PseudoList;
import cgeo.geocaching.list.StoredList;
import cgeo.geocaching.maps.MapActivity;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.ui.dialog.Dialogs.ItemWithIcon;
import cgeo.geocaching.utils.ImageUtils;

import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.List;

public class CreateShortcutActivity extends AbstractActionBarActivity {

    private static class Shortcut implements ItemWithIcon {

        @StringRes
        private final int titleResourceId;
        @DrawableRes
        private final int drawableResourceId;
        private final Intent intent;

        /**
         * shortcut with a separate icon
         */
        Shortcut(@StringRes final int titleResourceId, @DrawableRes final int drawableResourceId, final Intent intent) {
            this.titleResourceId = titleResourceId;
            this.drawableResourceId = drawableResourceId;
            this.intent = intent;
        }

        @Override
        @DrawableRes
        public int getIcon() {
            return drawableResourceId;
        }

        @Override
        @NonNull
        public String toString() {
            return CgeoApplication.getInstance().getString(titleResourceId);
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // init
        setTheme();

        promptForShortcut();
    }

    private void promptForShortcut() {
        final List<Shortcut> shortcuts = new ArrayList<>();

        shortcuts.add(new Shortcut(R.string.live_map_button, R.drawable.main_live, new Intent(this, MapActivity.class)));
        shortcuts.add(new Shortcut(R.string.caches_nearby_button, R.drawable.main_nearby, CacheListActivity.getNearestIntent(this)));

        // TODO: make logging activities ask for cache/trackable when being invoked externally
        // shortcuts.add(new Shortcut(R.string.cache_menu_visit, new Intent(this, LogCacheActivity.class)));
        // shortcuts.add(new Shortcut(R.string.trackable_log_touch, new Intent(this, LogTrackableActivity.class)));

        final Shortcut offlineShortcut = new Shortcut(R.string.list_title, R.drawable.main_stored, null);
        shortcuts.add(offlineShortcut);
        final Intent allIntent = new Intent(this, CacheListActivity.class);
        allIntent.putExtra(Intents.EXTRA_LIST_ID, PseudoList.ALL_LIST.id);
        shortcuts.add(new Shortcut(R.string.list_all_lists, R.drawable.main_stored, allIntent));
        shortcuts.add(new Shortcut(R.string.advanced_search_button, R.drawable.main_search, new Intent(this, SearchActivity.class)));
        shortcuts.add(new Shortcut(R.string.any_button, R.drawable.main_any, new Intent(this, NavigateAnyPointActivity.class)));
        shortcuts.add(new Shortcut(R.string.menu_history, R.drawable.main_stored, CacheListActivity.getHistoryIntent(this)));

        Dialogs.select(this, getString(R.string.create_shortcut), shortcuts, shortcut -> {
            if (offlineShortcut.equals(shortcut)) {
                promptForListShortcut();
            } else {
                createShortcutAndFinish(shortcut.toString(), shortcut.intent, shortcut.drawableResourceId);
            }
        });
    }

    protected void promptForListShortcut() {
        new StoredList.UserInterface(this).promptForListSelection(R.string.create_shortcut, this::createOfflineListShortcut, true, PseudoList.NEW_LIST.id);
    }

    protected void createOfflineListShortcut(final int listId) {
        final StoredList list = DataStore.getList(listId);
        // target to be executed by the shortcut
        final Intent targetIntent = new Intent(this, CacheListActivity.class);
        targetIntent.putExtra(Intents.EXTRA_LIST_ID, list.id);

        // shortcut to be returned
        createShortcutAndFinish(list.title, targetIntent, R.drawable.main_stored);
    }

    private void createShortcutAndFinish(final String title, final Intent targetIntent, @DrawableRes final int iconResourceId) {
        final Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, targetIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, title);
        if (iconResourceId == R.drawable.cgeo) {
            final ShortcutIconResource iconResource = Intent.ShortcutIconResource.fromContext(this, iconResourceId);
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);
        } else {
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, createOverlay(iconResourceId));
        }

        setResult(RESULT_OK, intent);

        // finish activity to return the shortcut
        finish();
    }

    private Bitmap createOverlay(@DrawableRes final int drawableResourceId) {
        final LayerDrawable layerDrawable = new LayerDrawable(new Drawable[] {
            ResourcesCompat.getDrawable(res, drawableResourceId, null),
            ResourcesCompat.getDrawable(res, R.drawable.cgeo_borderless, null)
        });
        layerDrawable.setLayerInset(0, 0, 0, 10, 10);
        layerDrawable.setLayerInset(1, 70, 70, 0, 0);
        return ImageUtils.convertToBitmap(layerDrawable);
    }

}
