package cgeo.geocaching.downloader;

import cgeo.geocaching.R;
import cgeo.geocaching.brouter.BRouterConstants;
import cgeo.geocaching.models.Download;
import cgeo.geocaching.storage.PersistableFolder;
import cgeo.geocaching.utils.CalendarUtils;
import cgeo.geocaching.utils.Formatter;
import cgeo.geocaching.utils.MatcherWrapper;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.regex.Pattern;

public class BRouterTileDownloader extends AbstractDownloader {
    private static final Pattern PATTERN_TILE = Pattern.compile("href=\"([E|W][0-9]+_[N|S][0-9]*\\.rd5)\">[E|W][0-9]+_[N|S][0-9]*\\.rd5<\\/a>[ ]*([0-9][0-9]-[A-Za-z]{1,3}-[0-9]{1,4}) [0-9][0-9]:[0-9][0-9][ ]*([1-9][0-9]{3,15})");
    // group 1: E50_N5.rd5
    // group 2: 21-Feb-2021
    // group 3: 12313423 (size in bytes)
    // 1126 Einträge
    private static final BRouterTileDownloader INSTANCE = new BRouterTileDownloader();

    private BRouterTileDownloader() {
        super(Download.DownloadType.DOWNLOADTYPE_BROUTER_TILES, R.string.brouter_downloadurl, R.string.brouter_name, R.string.brouter_info, R.string.brouter_projecturl, 0, PersistableFolder.ROUTING_TILES);
        overwrite = true; // silently overwrite already existing files
        useCompanionFiles = false; // use single uri, and no companion files
        forceExtension = BRouterConstants.BROUTER_TILE_FILEEXTENSION;
    }

    @Override
    protected void analyzePage(final Uri uri, final List<Download> list, final String page) {
        final MatcherWrapper matchMap = new MatcherWrapper(PATTERN_TILE, page);
        while (matchMap.find()) {
            final Download offlineMap = new Download(matchMap.group(1), Uri.parse(uri + matchMap.group(1)), false, CalendarUtils.yearMonthDay(CalendarUtils.parseDayMonthYearUS(matchMap.group(2))), Formatter.formatBytes(Long.parseLong(matchMap.group(3))), offlineMapType);
            list.add(offlineMap);
        }
    }

    @Override
    protected Download checkUpdateFor(final String page, final String remoteUrl, final String remoteFilename) {
        final MatcherWrapper matchMap = new MatcherWrapper(PATTERN_TILE, page);
        while (matchMap.find()) {
            final String filename = matchMap.group(1);
            if (filename.equals(remoteFilename)) {
                return new Download(filename, Uri.parse(mapBase + filename), false, CalendarUtils.yearMonthDay(CalendarUtils.parseDayMonthYearUS(matchMap.group(2))), Formatter.formatBytes(Long.parseLong(matchMap.group(3))), offlineMapType);
            }
        }
        return null;
    }

    // BRouter uses a single download page, need to map here to its fixed address
    @Override
    protected String getUpdatePageUrl(final String downloadPageUrl) {
        return mapBase.toString();
    }

    @NonNull
    public static BRouterTileDownloader getInstance() {
        return INSTANCE;
    }

}
