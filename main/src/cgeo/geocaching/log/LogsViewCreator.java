package cgeo.geocaching.log;

import cgeo.geocaching.ImagesActivity;
import cgeo.geocaching.R;
import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.network.SmileyImage;
import cgeo.geocaching.ui.AbstractCachingListViewPageViewCreator;
import cgeo.geocaching.ui.AnchorAwareLinkMovementMethod;
import cgeo.geocaching.ui.DecryptTextClickListener;
import cgeo.geocaching.ui.FastScrollListener;
import cgeo.geocaching.ui.dialog.ContextMenuDialog;
import cgeo.geocaching.utils.ClipboardUtils;
import cgeo.geocaching.utils.Formatter;
import cgeo.geocaching.utils.HtmlUtils;
import cgeo.geocaching.utils.ShareUtils;
import cgeo.geocaching.utils.TextUtils;
import cgeo.geocaching.utils.TranslationUtils;
import cgeo.geocaching.utils.UnknownTagsHandler;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.text.HtmlCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

public abstract class LogsViewCreator extends AbstractCachingListViewPageViewCreator {

    protected final AbstractActionBarActivity activity;

    public LogsViewCreator(final AbstractActionBarActivity activity) {
        this.activity = activity;
    }

    @Override
    public ListView getDispatchedView(final ViewGroup parentView) {
        if (!isValid()) {
            return null;
        }

        final List<LogEntry> logs = getLogs();

        view = (ListView) activity.getLayoutInflater().inflate(R.layout.logs_page, parentView, false);
        addHeaderView();
        view.setAdapter(new ArrayAdapter<LogEntry>(activity, R.layout.logs_item, logs) {

            @Override
            @NonNull
            public View getView(final int position, final View convertView, @NonNull final ViewGroup parent) {
                View rowView = convertView;
                if (rowView == null) {
                    rowView = activity.getLayoutInflater().inflate(R.layout.logs_item, parent, false);
                }
                LogViewHolder holder = (LogViewHolder) rowView.getTag();
                if (holder == null) {
                    holder = new LogViewHolder(rowView);
                }
                holder.setPosition(position);

                final LogEntry log = getItem(position);
                if (log != null) {
                    fillViewHolder(convertView, holder, log);
                }
                return rowView;
            }
        });
        view.setOnScrollListener(new FastScrollListener(view));

        return view;
    }

    protected void fillViewHolder(@SuppressWarnings("unused") final View convertView, final LogViewHolder holder, final LogEntry log) {
        if (log.date > 0) {
            holder.binding.added.setText(Formatter.formatShortDateVerbally(log.date));
            holder.binding.added.setVisibility(View.VISIBLE);
        } else {
            holder.binding.added.setVisibility(View.GONE);
        }

        holder.binding.type.setText(log.logType.getL10n());

        holder.binding.author.setText(StringEscapeUtils.unescapeHtml4(log.author));

        fillCountOrLocation(holder, log);

        // log text, avoid parsing HTML if not necessary
        if (TextUtils.containsHtml(log.log)) {
            final UnknownTagsHandler unknownTagsHandler = new UnknownTagsHandler();
            holder.binding.log.setText(TextUtils.trimSpanned(HtmlCompat.fromHtml(log.getDisplayText(), HtmlCompat.FROM_HTML_MODE_LEGACY, new SmileyImage(getGeocode(), holder.binding.log), unknownTagsHandler)), TextView.BufferType.SPANNABLE);
        } else {
            holder.binding.log.setText(log.log, TextView.BufferType.SPANNABLE);
        }

        // images
        if (log.hasLogImages()) {
            holder.binding.logImages.setText(log.getImageTitles());
            holder.binding.logImages.setVisibility(View.VISIBLE);
            holder.binding.logImages.setOnClickListener(v -> ImagesActivity.startActivity(activity, getGeocode(), new ArrayList<>(log.logImages)));
        } else {
            holder.binding.logImages.setVisibility(View.GONE);
        }

        // colored marker
        final int marker = log.logType.markerId;
        if (marker != 0) {
            holder.binding.logMark.setVisibility(View.VISIBLE);
            holder.binding.logMark.setImageResource(marker);
        } else {
            holder.binding.logMark.setVisibility(View.GONE);
        }

        holder.binding.author.setOnClickListener(createUserActionsListener(log));
        holder.binding.log.setMovementMethod(AnchorAwareLinkMovementMethod.getInstance());

        final View.OnClickListener logContextMenuClickListener = createOnLogClickListener(holder, log);

        holder.binding.log.setOnClickListener(logContextMenuClickListener);
        holder.binding.detailBox.setOnClickListener(logContextMenuClickListener);
    }

    protected View.OnClickListener createOnLogClickListener(final LogViewHolder holder, final LogEntry log) {
        return v -> {
            final String author = StringEscapeUtils.unescapeHtml4(log.author);
            final String title = activity.getString(R.string.cache_log_menu_popup_title, author);

            final ContextMenuDialog ctxMenu = new ContextMenuDialog(activity)
                    .setTitle(title)
                    .addItem(R.string.cache_log_menu_decrypt, 0, new DecryptTextClickListener(holder.binding.log))
                    .addItem(activity.getString(R.string.copy_to_clipboard), R.drawable.ic_menu_copy, i -> {
                        ClipboardUtils.copyToClipboard(holder.binding.log.getText().toString());
                        activity.showToast(activity.getString(R.string.clipboard_copy_ok));
                    })
                    .addItem(R.string.context_share_as_text, R.drawable.ic_menu_share, it ->
                            ShareUtils.sharePlainText(activity, holder.binding.log.getText().toString()))
                    .addItem(activity.getString(R.string.translate_to_sys_lang, Locale.getDefault().getDisplayLanguage()),
                            R.drawable.ic_menu_translate, it -> TranslationUtils.startActivityTranslate(activity, Locale.getDefault().getLanguage(), HtmlUtils.extractText(log.log)));
            final boolean localeIsEnglish = StringUtils.equals(Locale.getDefault().getLanguage(), Locale.ENGLISH.getLanguage());

            if (!localeIsEnglish) {
                ctxMenu.addItem(R.string.translate_to_english, R.drawable.ic_menu_translate, it ->
                        TranslationUtils.startActivityTranslate(activity, Locale.ENGLISH.getLanguage(), HtmlUtils.extractText(log.log)));
            }
            extendContextMenu(ctxMenu, log).show();
        };

    }

    /** for subclasses to overwrite and add own entries */
    protected ContextMenuDialog extendContextMenu(final ContextMenuDialog ctxMenu, final LogEntry log) {
        return ctxMenu;
    }

    protected abstract View.OnClickListener createUserActionsListener(LogEntry log);

    protected abstract String getGeocode();

    protected abstract List<LogEntry> getLogs();

    protected abstract void addHeaderView();

    protected abstract void fillCountOrLocation(LogViewHolder holder, LogEntry log);

    protected abstract boolean isValid();

}
