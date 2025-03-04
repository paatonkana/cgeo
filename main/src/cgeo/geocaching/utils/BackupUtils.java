package cgeo.geocaching.utils;

import cgeo.geocaching.InstallWizardActivity;
import cgeo.geocaching.MainActivity;
import cgeo.geocaching.R;
import cgeo.geocaching.settings.BackupSeekbarPreference;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.ContentStorage;
import cgeo.geocaching.storage.ContentStorageActivityHelper;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.storage.Folder;
import cgeo.geocaching.storage.FolderUtils;
import cgeo.geocaching.storage.PersistableFolder;
import cgeo.geocaching.storage.PersistableUri;
import cgeo.geocaching.storage.extension.OneTimeDialogs;
import cgeo.geocaching.ui.dialog.Dialogs;
import static cgeo.geocaching.utils.SettingsUtils.SettingsType.TYPE_STRING;
import static cgeo.geocaching.utils.SettingsUtils.SettingsType.TYPE_UNKNOWN;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Xml;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.schedulers.Schedulers;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;


public class BackupUtils {
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_VALUE = "value";
    private static final String TAG_MAP = "map";
    private static final String SETTINGS_FILENAME = "cgeo-settings.xml";

    private final Activity activityContext;

    public BackupUtils(final Activity activityContext) {
        this.activityContext = activityContext;
    }


    /* Public methods containing question dialogs, etc */

    public void selectBackupDirIntent (final ContentStorageActivityHelper contentStorageHelper) {
        Toast.makeText(activityContext, R.string.init_backup_restore_different_backup_explanation, Toast.LENGTH_LONG).show();
        contentStorageHelper.selectFolder(PersistableFolder.BACKUP.getUri(), f -> restore(f, contentStorageHelper));
    }

    /**
     * Show restore dialog
     */
    @SuppressLint("SetTextI18n")
    public void restore(final Folder backupDir, final ContentStorageActivityHelper contentStorageActivityHelper) {

        if (backupDir == null) {
            return;
        }

        if (!hasBackup(backupDir)) {
            Toast.makeText(activityContext, R.string.init_backup_no_backup_available, Toast.LENGTH_LONG).show();
            return;
        }

        // We are using ContextThemeWrapper to prevent crashes caused by missing attribute definitions when starting the dialog from MainActivity
        final Context c = new ContextThemeWrapper(activityContext, Settings.isLightSkin() ? R.style.Dialog_Alert_light : R.style.Dialog_Alert);
        final View content = LayoutInflater.from(c).inflate(R.layout.restore_dialog, null);
        final CheckBox databaseCheckbox = content.findViewById(R.id.database_check_box);
        final CheckBox settingsCheckbox = content.findViewById(R.id.settings_check_box);
        final TextView warningText = content.findViewById(R.id.warning);

        if (getDatabaseBackupTime(backupDir) != 0) {
            databaseCheckbox.setText(activityContext.getString(R.string.init_backup_caches) + "\n(" + Formatter.formatShortDateTime(getDatabaseBackupTime(backupDir)) + ")");
            databaseCheckbox.setEnabled(true);
            databaseCheckbox.setChecked(true);
        } else {
            databaseCheckbox.setText(activityContext.getString(R.string.init_backup_caches) + "\n(" + activityContext.getString(R.string.init_backup_unavailable) + ")");
        }
        if (getSettingsBackupTime(backupDir) != 0) {
            settingsCheckbox.setText(activityContext.getString(R.string.init_backup_program_settings) + "\n(" + Formatter.formatShortDateTime(getSettingsBackupTime(backupDir)) + ")");
            settingsCheckbox.setEnabled(true);
            settingsCheckbox.setChecked(true);
        } else {
            settingsCheckbox.setText(activityContext.getString(R.string.init_backup_program_settings) + "\n(" + activityContext.getString(R.string.init_backup_unavailable) + ")");
        }

        final AlertDialog dialog = Dialogs.newBuilder(activityContext)
                .setTitle(activityContext.getString(R.string.init_backup_restore))
                .setView(content)
                .setPositiveButton(activityContext.getString(android.R.string.yes), (alertDialog, id) -> {
                    alertDialog.dismiss();
                    restoreInternal(activityContext, contentStorageActivityHelper, backupDir, databaseCheckbox.isChecked(), settingsCheckbox.isChecked());
                })
                .setNegativeButton(activityContext.getString(android.R.string.no), (alertDialog, id) -> {
                    alertDialog.cancel();
                })
                .create();

        dialog.setOwnerActivity(activityContext);
        dialog.show();

        final Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        updateRestoreDialog(button, databaseCheckbox, settingsCheckbox, warningText);
        databaseCheckbox.setOnClickListener(checkbox -> updateRestoreDialog(button, databaseCheckbox, settingsCheckbox, warningText));
        settingsCheckbox.setOnClickListener(checkbox -> updateRestoreDialog(button, databaseCheckbox, settingsCheckbox, warningText));
    }

    private void updateRestoreDialog(final Button button, final CheckBox databaseCheckbox, final CheckBox settingsCheckbox, final TextView warningText) {

        button.setEnabled(databaseCheckbox.isChecked() || settingsCheckbox.isChecked());

        final int caches = DataStore.getAllCachesCount();
        if (databaseCheckbox.isChecked() && caches > 0) {
            warningText.setVisibility(View.VISIBLE);
            warningText.setText(activityContext.getString(settingsCheckbox.isChecked() ? R.string.restore_confirm_overwrite_database_and_settings : R.string.restore_confirm_overwrite_database, activityContext.getResources().getQuantityString(R.plurals.cache_counts, caches, caches)));
        } else if (settingsCheckbox.isChecked() && caches > 0) {
            warningText.setVisibility(View.VISIBLE);
            warningText.setText(R.string.restore_confirm_overwrite_settings);
        } else {
            warningText.setVisibility(View.GONE);
        }
    }

    public void restoreInternal(final Activity activityContext, final ContentStorageActivityHelper contentStorageActivityHelper, final Folder backupDir, final boolean database, final boolean settings) {
        final Consumer<String> consumer = resultString -> {

            boolean restartNeeded = false;
            final ArrayList<ImmutableTriple<PersistableFolder, String, String>> currentFolderValues = new ArrayList<>();
            final ArrayList<ImmutableTriple<PersistableUri, String, String>> currentUriValues = new ArrayList<>();

            if (settings) {
                // build a list of folders currently set and a list of remaining folders
                final ArrayList<ImmutablePair<PersistableFolder, String>> unsetFolders = new ArrayList<>();
                for (PersistableFolder folder : PersistableFolder.values()) {
                    final String value = Settings.getPersistableFolderRaw(folder);
                    if (value != null) {
                        currentFolderValues.add(new ImmutableTriple<>(folder, activityContext.getString(folder.getPrefKeyId()), value));
                    } else {
                        unsetFolders.add(new ImmutablePair<>(folder, activityContext.getString(folder.getPrefKeyId())));
                    }
                }

                // same for files
                final ArrayList<ImmutablePair<PersistableUri, String>> unsetUris = new ArrayList<>();
                for (PersistableUri uri : PersistableUri.values()) {
                    final String value = Settings.getPersistableUriRaw(uri);
                    if (value != null) {
                        currentUriValues.add(new ImmutableTriple<>(uri, activityContext.getString(uri.getPrefKeyId()), value));
                    } else {
                        unsetUris.add(new ImmutablePair<>(uri, activityContext.getString(uri.getPrefKeyId())));
                    }
                }

                if (!resultString.isEmpty()) {
                    resultString += "\n\n";
                }
                restartNeeded = restoreSettingsInternal(backupDir, currentFolderValues, unsetFolders, currentUriValues, unsetUris);

                if (!restartNeeded) {
                    resultString += activityContext.getString(R.string.init_restore_settings_failed);
                }
            }

            // check if folder settings changed and request grants, if necessary
            if (settings && (currentFolderValues.size() > 0 || currentUriValues.size() > 0)) {
                regrantAccess(activityContext, contentStorageActivityHelper, currentFolderValues, currentUriValues, restartNeeded, resultString);
            } else {
                finishRestoreInternal(activityContext, restartNeeded, resultString);
            }
        };

        if (database) {
            restoreDatabaseInternal(backupDir, consumer);
        } else {
            consumer.accept("");
        }
    }

    private void regrantAccess(final Activity activityContext, final ContentStorageActivityHelper contentStorageActivityHelper, final ArrayList<ImmutableTriple<PersistableFolder, String, String>> currentFolderValues, final ArrayList<ImmutableTriple<PersistableUri, String, String>> currentUriValues, final boolean restartNeeded, final String resultString) {
        if (currentFolderValues.size() > 0) {
            final ImmutableTriple<PersistableFolder, String, String> current = currentFolderValues.get(0);
            final Folder folderToBeRestored = Folder.fromConfig(current.right);

            Dialogs.confirm(activityContext,
                activityContext.getString(R.string.init_backup_settings_restore),
                String.format(activityContext.getString(R.string.settings_folder_changed), activityContext.getString(currentFolderValues.get(0).left.getNameKeyId()), folderToBeRestored.toUserDisplayableString(), activityContext.getString(android.R.string.cancel), activityContext.getString(android.R.string.ok)),
                activityContext.getString(android.R.string.ok),
                (d, v) -> {
                    contentStorageActivityHelper.restorePersistableFolder(current.left, current.left.getUriForFolder(folderToBeRestored), v2 -> {
                        currentFolderValues.remove(0);
                        regrantAccess(activityContext, contentStorageActivityHelper, currentFolderValues, currentUriValues, restartNeeded, resultString);
                    });
                },
                d2 -> {
                    currentFolderValues.remove(0);
                    regrantAccess(activityContext, contentStorageActivityHelper, currentFolderValues, currentUriValues, restartNeeded, resultString);
                });
        } else if (currentUriValues.size() > 0) {
            final Uri uriToBeRestored = Uri.parse(currentUriValues.get(0).right);
            final String temp = uriToBeRestored.getPath();
            final String displayName = temp.substring(temp.lastIndexOf('/') + 1);

            Dialogs.confirm(activityContext,
                activityContext.getString(R.string.init_backup_settings_restore),
                String.format(activityContext.getString(R.string.settings_file_changed), activityContext.getString(currentUriValues.get(0).left.getNameKeyId()), displayName, activityContext.getString(android.R.string.cancel), activityContext.getString(android.R.string.ok)),
                activityContext.getString(android.R.string.ok),
                (d, v) -> {
                    contentStorageActivityHelper.restorePersistableUri(PersistableUri.TRACK, uriToBeRestored, v2 -> {
                        currentUriValues.remove(0);
                        regrantAccess(activityContext, contentStorageActivityHelper, currentFolderValues, currentUriValues, restartNeeded, resultString);
                    });
                },
                d2 -> {
                    currentUriValues.remove(0);
                    regrantAccess(activityContext, contentStorageActivityHelper, currentFolderValues, currentUriValues, restartNeeded, resultString);
                });
        } else {
            finishRestoreInternal(activityContext, restartNeeded, resultString);
        }
    }

    private void finishRestoreInternal(final Activity activityContext, final boolean restartNeeded, final String resultString) {
        // finish restore settings
        if (restartNeeded && !(activityContext instanceof InstallWizardActivity)) {
            Dialogs.confirmYesNo(activityContext, R.string.init_restore_restored, resultString + activityContext.getString(R.string.settings_restart), (dialog2, which2) -> ProcessUtils.restartApplication(activityContext));
        } else {
            Dialogs.message(activityContext, R.string.init_restore_restored, resultString);
        }
    }

    public void deleteBackupHistoryDialog(final BackupSeekbarPreference preference, final int newValue) {
        final List<ContentStorage.FileInformation> dirs = getDirsToRemove(newValue + 1);

        if (dirs != null) {
            final View content = activityContext.getLayoutInflater().inflate(R.layout.dialog_text_checkbox, null);
            final CheckBox checkbox = (CheckBox) content.findViewById(R.id.check_box);
            final TextView textView = (TextView) content.findViewById(R.id.message);
            textView.setText(R.string.init_backup_history_delete_warning);
            checkbox.setText(R.string.init_user_confirmation);

            final AlertDialog alertDialog = new AlertDialog.Builder(new ContextThemeWrapper(activityContext, R.style.Dialog_Alert))
                .setView(content)
                .setTitle(R.string.init_backup_backup_history)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    removeDirs(dirs);
                })
                .setNeutralButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                .setOnCancelListener(dialog -> preference.setValue(Math.min(newValue + dirs.size(), activityContext.getResources().getInteger(R.integer.backup_history_length_max))))
                .create();

            alertDialog.show();
            alertDialog.setOwnerActivity(activityContext);

            final Button button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setEnabled(false);
            checkbox.setOnClickListener(check -> button.setEnabled(checkbox.isChecked()));
        }

    }

    /**
     * Create a backup after confirming to overwrite the existing backup.
     */
    public void backup(final Runnable runAfterwards) {

        // avoid overwriting an existing backup with an empty database
        // (can happen directly after reinstalling the app)
        if (DataStore.getAllCachesCount() == 0) {
            Toast.makeText(activityContext, R.string.init_backup_unnecessary, Toast.LENGTH_LONG).show();
            return;
        }

        final List<ContentStorage.FileInformation> dirs = getDirsToRemove(Settings.allowedBackupsNumber());
        if (dirs != null) {
            Dialogs.advancedOneTimeMessage(activityContext, OneTimeDialogs.DialogType.DATABASE_CONFIRM_OVERWRITE, activityContext.getString(R.string.init_backup_backup), activityContext.getString(R.string.backup_confirm_overwrite, getBackupDateTime(dirs.get(dirs.size() - 1).dirLocation)), null, true, null, () -> {
                removeDirs(dirs);
                backupInternal(runAfterwards);
            });
        } else {
            backupInternal(runAfterwards);
        }
    }


    /**
     * Private methods containing the real backup process
     */

    // returns true on success
    private boolean restoreSettingsInternal(final Folder backupDir, final ArrayList<ImmutableTriple<PersistableFolder, String, String>> currentFolderValues, final ArrayList<ImmutablePair<PersistableFolder, String>> unsetFolders, final ArrayList<ImmutableTriple<PersistableUri, String, String>> currentUriValues, final ArrayList<ImmutablePair<PersistableUri, String>> unsetUris) {
        try {
            // open file
            final InputStream file = ContentStorage.get().openForRead(getSettingsFile(backupDir).uri);

            // open shared prefs for writing
            final SharedPreferences prefs = activityContext.getSharedPreferences(ApplicationSettings.getPreferencesName(), Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = prefs.edit();

            // parse xml
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            final XmlPullParser parser = factory.newPullParser();
            parser.setInput(file, null);

            // retrieve data
            boolean inTag = false;
            SettingsUtils.SettingsType type = TYPE_UNKNOWN;
            String key = "";
            String value = "";
            int eventType = 0;

            eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.getName().equals(TAG_MAP)) {
                        inTag = true;
                    } else if (inTag) {
                        type = SettingsUtils.getType(parser.getName());
                        key = "";
                        value = "";

                        // read attributes
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            final String name = parser.getAttributeName(i);
                            if (name.equals(ATTRIBUTE_NAME)) {
                                key = parser.getAttributeValue(i);
                            } else if (name.equals(ATTRIBUTE_VALUE) && !type.equals(TYPE_STRING)) {
                                value = parser.getAttributeValue(i);
                            } else {
                                throw new XmlPullParserException("unknown attribute" + parser.getAttributeName(i));
                            }
                        }
                    } else {
                        throw new XmlPullParserException("unknown entity " + parser.getName());
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (inTag) {
                        if (parser.getName().equals(TAG_MAP)) {
                            inTag = false;
                        } else if (SettingsUtils.getType(parser.getName()) == type) {
                            boolean handled = false;
                            if (type == TYPE_STRING) {
                                // check for persistable folder settings
                                handled = checkForFolderSetting(currentFolderValues, unsetFolders, key, value);
                                if (!handled) {
                                    handled = checkForUriSetting(currentUriValues, unsetUris, key, value);
                                }
                            }
                            if (!handled) {
                                SettingsUtils.putValue(editor, type, key, value);
                            }
                            type = TYPE_UNKNOWN;
                        } else {
                            throw new XmlPullParserException("invalid structure: unexpected closing tag " + parser.getName());
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT && inTag && type.equals(TYPE_STRING)) {
                    value = parser.getText();
                }
                eventType = parser.next();
            }

            // close shared prefs
            if (!editor.commit()) {
                throw new XmlPullParserException("could not commit changed preferences");
            }
            return true;
        } catch (NullPointerException | IOException | XmlPullParserException | NumberFormatException e) {
            final String error = e.getMessage();
            if (null != error) {
                Log.d("error reading settings file: " + error);
            }
            Dialogs.message(activityContext, R.string.init_backup_settings_restore, R.string.settings_readingerror);
            return false;
        }
    }

    private boolean checkForFolderSetting(final ArrayList<ImmutableTriple<PersistableFolder, String, String>> currentFolderValues, final ArrayList<ImmutablePair<PersistableFolder, String>> unsetFolders, final String key, final String value) {
        // check if persistable folder settings differ
        for (int i = 0; i < currentFolderValues.size(); i++) {
            final ImmutableTriple<PersistableFolder, String, String> current = currentFolderValues.get(i);
            if (current.middle.equals(key)) {
                if (!current.right.equals(value)) {
                    currentFolderValues.add(new ImmutableTriple<>(current.left, current.middle, value));
                }
                currentFolderValues.remove(i);
                return true;
            }
        }

        // check if this is a folder grant setting for a folder currently not set
        for (int i = 0; i < unsetFolders.size(); i++) {
            final ImmutablePair<PersistableFolder, String> current = unsetFolders.get(i);
            if (current.right.equals(key)) {
                currentFolderValues.add(new ImmutableTriple<>(current.left, current.right, value));
                unsetFolders.remove(i);
                return true;
            }
        }

        // no folder-related setting found
        return false;
    }

    private boolean checkForUriSetting(final ArrayList<ImmutableTriple<PersistableUri, String, String>> currentUriValues, final ArrayList<ImmutablePair<PersistableUri, String>> unsetUris, final String key, final String value) {
        // check if persistable uri settings differ
        for (int i = 0; i < currentUriValues.size(); i++) {
            final ImmutableTriple<PersistableUri, String, String> current = currentUriValues.get(i);
            if (current.middle.equals(key)) {
                if (!current.right.equals(value)) {
                    currentUriValues.add(new ImmutableTriple<>(current.left, current.middle, value));
                }
                currentUriValues.remove(i);
                return true;
            }
        }

        // check if this is a uri grant setting for a uri currently not set
        for (int i = 0; i < unsetUris.size(); i++) {
            final ImmutablePair<PersistableUri, String> current = unsetUris.get(i);
            if (current.right.equals(key)) {
                currentUriValues.add(new ImmutableTriple<>(current.left, current.right, value));
                unsetUris.remove(i);
                return true;
            }
        }

        // no uri-related setting found
        return false;
    }

    private void restoreDatabaseInternal(final Folder backupDir, final Consumer<String> consumer) {
        final ContentStorage.FileInformation dbFile = getDatabaseFile(backupDir);

        final ProgressDialog dialog = ProgressDialog.show(activityContext, activityContext.getString(R.string.init_backup_restore), activityContext.getString(R.string.init_restore_running), true, false);
        final StringBuilder stringBuilder = new StringBuilder();
        AndroidRxUtils.andThenOnUi(Schedulers.io(), () -> stringBuilder.append(DataStore.restoreDatabaseInternal(activityContext, dbFile.uri)), () -> {
            dialog.dismiss();

            if (activityContext instanceof MainActivity) {
                ((MainActivity) activityContext).updateCacheCounter();
            }
            consumer.accept(stringBuilder.toString());
        });
    }

    private void backupInternal(final Runnable runAfterwards) {
        final Folder backupDir = getNewBackupFolder(System.currentTimeMillis());
        if (backupDir == null) {
            Toast.makeText(activityContext, R.string.init_backup_folder_exists_error, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean settingsResult = createSettingsBackupInternal(backupDir, Settings.getBackupLoginData());
        final Consumer<Boolean> consumer = dbResult -> {
            showBackupCompletedStatusDialog(backupDir, settingsResult, dbResult);

            if (runAfterwards != null) {
                runAfterwards.run();
            }
        };
        createDatabaseBackupInternal(backupDir, consumer);
    }

    private boolean createSettingsBackupInternal(final Folder backupDir, final Boolean fullBackup) {
        final SharedPreferences prefs = activityContext.getSharedPreferences(ApplicationSettings.getPreferencesName(), Context.MODE_PRIVATE);
        final Map<String, ?> keys = prefs.getAll();
        final HashSet<String> ignoreKeys = new HashSet<>();

        // if a backup without account data is requested add all account related preference keys to the ignore set
        if (!fullBackup) {
            Collections.addAll(ignoreKeys,
                    activityContext.getString(R.string.pref_username), activityContext.getString(R.string.pref_password), activityContext.getString(R.string.pref_memberstatus), activityContext.getString(R.string.pref_gccustomdate),
                    activityContext.getString(R.string.pref_ecusername), activityContext.getString(R.string.pref_ecpassword),
                    activityContext.getString(R.string.pref_user_vote), activityContext.getString(R.string.pref_pass_vote),
                    activityContext.getString(R.string.pref_twitter), activityContext.getString(R.string.pref_temp_twitter_token_secret), activityContext.getString(R.string.pref_temp_twitter_token_public), activityContext.getString(R.string.pref_twitter_token_secret), activityContext.getString(R.string.pref_twitter_token_public),
                    activityContext.getString(R.string.pref_ocde_tokensecret), activityContext.getString(R.string.pref_ocde_tokenpublic), activityContext.getString(R.string.pref_temp_ocde_token_secret), activityContext.getString(R.string.pref_temp_ocde_token_public),
                    activityContext.getString(R.string.pref_ocpl_tokensecret), activityContext.getString(R.string.pref_ocpl_tokenpublic), activityContext.getString(R.string.pref_temp_ocpl_token_secret), activityContext.getString(R.string.pref_temp_ocpl_token_public),
                    activityContext.getString(R.string.pref_ocnl_tokensecret), activityContext.getString(R.string.pref_ocnl_tokenpublic), activityContext.getString(R.string.pref_temp_ocnl_token_secret), activityContext.getString(R.string.pref_temp_ocnl_token_public),
                    activityContext.getString(R.string.pref_ocus_tokensecret), activityContext.getString(R.string.pref_ocus_tokenpublic), activityContext.getString(R.string.pref_temp_ocus_token_secret), activityContext.getString(R.string.pref_temp_ocus_token_public),
                    activityContext.getString(R.string.pref_ocro_tokensecret), activityContext.getString(R.string.pref_ocro_tokenpublic), activityContext.getString(R.string.pref_temp_ocro_token_secret), activityContext.getString(R.string.pref_temp_ocro_token_public),
                    activityContext.getString(R.string.pref_ocuk2_tokensecret), activityContext.getString(R.string.pref_ocuk2_tokenpublic), activityContext.getString(R.string.pref_temp_ocuk2_token_secret), activityContext.getString(R.string.pref_temp_ocuk2_token_public),
                    activityContext.getString(R.string.pref_su_tokensecret), activityContext.getString(R.string.pref_su_tokenpublic), activityContext.getString(R.string.pref_temp_su_token_secret), activityContext.getString(R.string.pref_temp_su_token_public),
                    activityContext.getString(R.string.pref_fakekey_geokrety_authorization)
            );
        }

        final Uri backupFile = ContentStorage.get().create(backupDir, SETTINGS_FILENAME);
        Writer writer = null;
        boolean success = true;
        try {

            final OutputStream os = ContentStorage.get().openForWrite(backupFile);
            writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);

            final XmlSerializer xmlSerializer = Xml.newSerializer();
            xmlSerializer.setOutput(writer);
            xmlSerializer.startDocument("UTF-8", true);

            xmlSerializer.startTag(null, TAG_MAP);
            for (Map.Entry<String, ?> entry : keys.entrySet()) {
                final Object value = entry.getValue();
                final String key = entry.getKey();
                if (!ignoreKeys.contains(key)) {
                    final SettingsUtils.SettingsType type = SettingsUtils.getType(value);
                    if (type == TYPE_STRING) {
                        xmlSerializer.startTag(null, type.getId());
                        xmlSerializer.attribute(null, ATTRIBUTE_NAME, key);
                        xmlSerializer.text(value.toString());
                        xmlSerializer.endTag(null, type.getId());
                    } else if (type != TYPE_UNKNOWN) {
                        xmlSerializer.startTag(null, type.getId());
                        xmlSerializer.attribute(null, ATTRIBUTE_NAME, key);
                        xmlSerializer.attribute(null, ATTRIBUTE_VALUE, value.toString());
                        xmlSerializer.endTag(null, type.getId());
                    }
                }
            }
            xmlSerializer.endTag(null, TAG_MAP);

            xmlSerializer.endDocument();
            xmlSerializer.flush();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            success = false;
            final String error = e.getMessage();
            if (null != error) {
                Log.e("error writing settings file: " + error);
            }
        }
        IOUtils.closeQuietly(writer);
        return success;
    }

    private void createDatabaseBackupInternal(final Folder backupDir, final Consumer<Boolean> consumer) {
        final ProgressDialog dialog = ProgressDialog.show(activityContext,
                activityContext.getString(R.string.init_backup),
                activityContext.getString(R.string.init_backup_running), true, false);
        AndroidRxUtils.andThenOnUi(Schedulers.io(), () -> DataStore.backupDatabaseInternal(backupDir), backupFile -> {
            dialog.dismiss();
            consumer.accept(backupFile != null);
        });
    }

    private void showBackupCompletedStatusDialog(final Folder backupDir, final Boolean settingsResult, final Boolean databaseResult) {
        String msg;
        final String title;
        if (settingsResult && databaseResult) {
            title = activityContext.getString(R.string.init_backup_finished);
            msg = activityContext.getString(R.string.backup_saved) + "\n" + backupDir.toUserDisplayableString();
        } else {
            title = activityContext.getString(R.string.init_backup_backup_failed);

            if (databaseResult != null) {
                msg = activityContext.getString(R.string.init_backup_success) + "\n" + backupDir.toUserDisplayableString() + "/" + DataStore.DB_FILE_NAME_BACKUP;
            } else {
                msg = activityContext.getString(R.string.init_backup_failed);
            }

            msg += "\n\n";

            if (settingsResult != null) {
                msg += activityContext.getString(R.string.settings_saved) + "\n" + backupDir.toUserDisplayableString() + "/" + SETTINGS_FILENAME;
            } else {
                msg += activityContext.getString(R.string.settings_savingerror);
            }
        }

        final ArrayList<Uri> files = new ArrayList<>();

        for (ContentStorage.FileInformation fi : ContentStorage.get().list(backupDir)) {
            files.add(fi.uri);
        }

        Dialogs.messageNeutral(activityContext, title, msg, R.string.cache_share_field,
            (dialog, which) -> ShareUtils.shareMultipleFiles(activityContext, files, R.string.init_backup_backup));
    }


    /* Methods for checking the backup availability */

    public static boolean hasBackup(final Folder backupDir) {
        return getDatabaseFile(backupDir) != null || getSettingsFile(backupDir) != null;
    }

    @Nullable
    private static ContentStorage.FileInformation getDatabaseFile(final Folder backupDir) {
        return ContentStorage.get().getFileInfo(backupDir, DataStore.DB_FILE_NAME_BACKUP);
    }

    @Nullable
    private static ContentStorage.FileInformation getSettingsFile(final Folder backupDir) {
        return ContentStorage.get().getFileInfo(backupDir, SETTINGS_FILENAME);
    }

    private static long getDatabaseBackupTime(final Folder backupDir) {
        final ContentStorage.FileInformation restoreFile = getDatabaseFile(backupDir);
        if (restoreFile == null) {
            return 0;
        }
        return restoreFile.lastModified;
    }

    private static long getSettingsBackupTime(final Folder backupDir) {
        final ContentStorage.FileInformation file = getSettingsFile(backupDir);
        if (file == null) {
            return 0;
        }
        return file.lastModified;
    }

    private static long getBackupTime(final Folder backupDir) {
        return backupDir == null ? 0 : Math.max(getDatabaseBackupTime(backupDir), getSettingsBackupTime(backupDir));
    }

    @NonNull
    public static String getNewestBackupDateTime() {
        return getBackupDateTime(newestBackupFolder());
    }

    @NonNull
    private static String getBackupDateTime(final Folder backupDir) {
        final long time = getBackupTime(backupDir);
        if (time == 0) {
            return StringUtils.EMPTY;
        }
        return Formatter.formatShortDateTime(time);
    }

    @Nullable
    public static Folder newestBackupFolder() {
        final ArrayList<ContentStorage.FileInformation> dirs = getExistingBackupFoldersSorted();
        return dirs == null ? null : dirs.get(dirs.size() - 1).dirLocation;
    }

    @Nullable
    private static ArrayList<ContentStorage.FileInformation> getExistingBackupFoldersSorted() {
        final ArrayList<ContentStorage.FileInformation> files = new ArrayList<>(ContentStorage.get().list(PersistableFolder.BACKUP.getFolder(), true, false));
        CollectionUtils.filter(files, s -> s.isDirectory && s.name.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2} (20|21|22|23|[01]\\d|\\d)((-[0-5]\\d){1,2})$"));
        return files.size() == 0 ? null : files;
    }

    @Nullable
    private List<ContentStorage.FileInformation> getDirsToRemove(final int maxBackupNumber) {
        final ArrayList<ContentStorage.FileInformation> dirs = getExistingBackupFoldersSorted();

        if (dirs == null || dirs.size() <= maxBackupNumber || maxBackupNumber >= activityContext.getResources().getInteger(R.integer.backup_history_length_max)) {
            Log.i("no old backups to remove");
            return null;
        }
        Log.e("old backups to remove: " + dirs);
        return dirs.subList(0, dirs.size() - maxBackupNumber);
    }

    private void removeDirs(final List<ContentStorage.FileInformation> dirs) {
        for (ContentStorage.FileInformation dir : dirs) {
            FolderUtils.get().deleteAll(dir.dirLocation);
            ContentStorage.get().delete(dir.dirLocation.getUri());
        }
    }

    @Nullable
    public static Folder getNewBackupFolder(final long timestamp) {
        if (ContentStorage.get().exists(PersistableFolder.BACKUP.getFolder(), Formatter.formatDateForFilename(timestamp))) {
            return null; // We don't want to overwrite a existing backup
        }
        final Folder subfolder = Folder.fromPersistableFolder(PersistableFolder.BACKUP, Formatter.formatDateForFilename(timestamp));
        ContentStorage.get().ensureFolder(subfolder, true);
        return subfolder;
    }
}
