package cgeo.geocaching.connector.lc;

import cgeo.geocaching.enumerations.CacheSize;
import cgeo.geocaching.enumerations.CacheType;
import cgeo.geocaching.enumerations.LoadFlags.SaveFlag;
import cgeo.geocaching.enumerations.WaypointType;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.location.Viewport;
import cgeo.geocaching.models.Geocache;
import cgeo.geocaching.models.Waypoint;
import cgeo.geocaching.network.Network;
import cgeo.geocaching.network.Parameters;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.utils.JsonUtils;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.SynchronizedDateFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Function;
import okhttp3.Response;

final class LCApi {

    private static final Boolean minimalFunction = true;

    private static final SynchronizedDateFormat DATE_FORMAT = new SynchronizedDateFormat("yyyy-MM-dd", Locale.getDefault());
    @NonNull
    private static final String API_HOST = "https://labs-api.geocaching.com/Api/Adventures/";

    private LCApi() {
        // utility class with static methods
    }

    @Nullable
    protected static Geocache searchByGeocode(final String geocode) {
        if (!Settings.isGCPremiumMember()) {
            return null;
        }
        try {
            final Response response = apiRequest(geocode.substring(2)).blockingGet();
            return importCacheFromJSON(response);
        } catch (final Exception ignored) {
            return null;
        }
    }

    @NonNull
    protected static Collection<Geocache> searchByBBox(final Viewport viewport) {

        if (!Settings.isGCPremiumMember() || viewport.getLatitudeSpan() == 0 || viewport.getLongitudeSpan() == 0) {
            return Collections.emptyList();
        }

        final double latcenter = (viewport.getLatitudeMax()  + viewport.getLatitudeMin())  / 2;
        final double loncenter = (viewport.getLongitudeMax() + viewport.getLongitudeMin()) / 2;
        final Geopoint center = new Geopoint(latcenter, loncenter);
        return searchByCenter(center);
    }

    @NonNull
    protected static Collection<Geocache> searchByCenter(final Geopoint center) {
        if (!Settings.isGCPremiumMember()) {
            return Collections.emptyList();
        }
        final Parameters params = new Parameters("skip", "0");
        params.add("take", "100");
        params.add("radiusMeters", "10000");
        params.add("origin.latitude", String.valueOf(center.getLatitude()));
        params.add("origin.longitude", String.valueOf(center.getLongitude()));
        try {
            final Response response = apiRequest("SearchV3", params).blockingGet();
            return importCachesFromJSON(response);
        } catch (final Exception ignored) {
            return Collections.emptyList();
        }
    }

    @NonNull
    private static Single<Response> apiRequest(final String uri) {
        return Network.getRequest(API_HOST + uri);
    }

    @NonNull
    private static Single<Response> apiRequest(final String uri, final Parameters params) {
        return apiRequest(uri, params, false);
    }

    @NonNull
    private static Single<Response> apiRequest(final String uri, final Parameters params, final boolean isRetry) {

        final Single<Response> response = Network.getRequest(API_HOST + uri, params);

        // retry at most one time
        return response.flatMap((Function<Response, Single<Response>>) response1 -> {
            if (!isRetry && response1.code() == 403) {
                return apiRequest(uri, params, true);
            }
            return Single.just(response1);
        });
    }

    @NonNull
    private static Geocache importCacheFromJSON(final Response response) {
        try {
            final JsonNode json = JsonUtils.reader.readTree(Network.getResponseData(response));
            return parseCacheDetail(json);
        } catch (final Exception e) {
            Log.w("_LC importCacheFromJSON", e);
            return null;
        }
    }

    @NonNull
    private static List<Geocache> importCachesFromJSON(final Response response) {
        try {
            final JsonNode json = JsonUtils.reader.readTree(Network.getResponseData(response));
            final JsonNode items = json.at("/Items");
            if (!items.isArray()) {
                return Collections.emptyList();
            }
            final List<Geocache> caches = new ArrayList<>(items.size());
            for (final JsonNode node : items) {
                final Geocache cache = parseCache(node);
                if (cache != null) {
                    caches.add(cache);
                }
            }
            return caches;
        } catch (final Exception e) {
            Log.w("_LC importCachesFromJSON", e);
            return Collections.emptyList();
        }
    }

    @Nullable
    private static Geocache parseCache(final JsonNode response) {
        try {
            final Geocache cache = new Geocache();
            final JsonNode location = response.at("/Location");
            final String firebaseDynamicLink = response.get("FirebaseDynamicLink").asText();
            final String[] segments = firebaseDynamicLink.split("/");
            final String id = segments[segments.length - 1];
            final String uuid = response.get("Id").asText();
            cache.setReliableLatLon(true);
            cache.setGeocode("LC" + uuid);
            cache.setCacheId(id);
            cache.setName(response.get("Title").asText());
            cache.setCoords(new Geopoint(location.get("Latitude").asText(), location.get("Longitude").asText()));
            cache.setType(CacheType.ADVLAB);
            cache.setDifficulty((float) 1);
            cache.setTerrain((float) 1);
            cache.setSize(CacheSize.getById("virtual"));
            cache.setFound(false);
            DataStore.saveCache(cache, EnumSet.of(SaveFlag.CACHE));
            return cache;
        } catch (final NullPointerException e) {
            Log.e("_LC LCApi.parseCache", e);
            return null;
        }
    }

    // Having a separate parser for details is required because the API provider
    // decided to use different upper/lower case wordings for the same entities

    @Nullable
    private static Geocache parseCacheDetail(final JsonNode response) {
        try {
            final Geocache cache = new Geocache();
            final JsonNode location = response.at("/Location");
            final String firebaseDynamicLink = response.get("FirebaseDynamicLink").asText();
            final String[] segments = firebaseDynamicLink.split("/");
            final String id = segments[segments.length - 1];
            final String uuid = response.get("Id").asText();
            final String ilink = response.get("KeyImageUrl").asText();
            final String desc = response.get("Description").asText();
            cache.setReliableLatLon(true);
            cache.setGeocode("LC" + uuid);
            cache.setCacheId(id);
            cache.setName(response.get("Title").asText());
            if (minimalFunction) {
                cache.setDescription("");
            } else {
                cache.setDescription("<img src=\"" + ilink + "\" </img><p><p>" + desc);
            }
            cache.setCoords(new Geopoint(location.get("Latitude").asText(), location.get("Longitude").asText()));
            cache.setType(CacheType.ADVLAB);
            cache.setDifficulty((float) 1);
            cache.setTerrain((float) 1);
            cache.setSize(CacheSize.getById("virtual"));
            cache.setFound(false);
            cache.setDisabled(false);
            cache.setHidden(parseDate(response.get("PublishedUtc").asText()));
            cache.setOwnerDisplayName(response.get("OwnerUsername").asText());
            cache.setWaypoints(parseWaypoints((ArrayNode) response.path("GeocacheSummaries")), false);
            cache.setDetailedUpdatedNow();
            DataStore.saveCache(cache, EnumSet.of(SaveFlag.DB));
            return cache;
        } catch (final NullPointerException e) {
            Log.e("_LC LCApi.parseCache", e);
            return null;
        }
    }

    @Nullable
    private static List<Waypoint> parseWaypoints(final ArrayNode wptsJson) {
        if (minimalFunction) {
            return null;
        }
        List<Waypoint> result = null;
        final Geopoint pointZero = new Geopoint(0, 0);
        int stageCounter = 0;
        for (final JsonNode wptResponse: wptsJson) {
            stageCounter++;
            try {
                final Waypoint wpt = new Waypoint(wptResponse.get("Title").asText(), WaypointType.PUZZLE, false);
                final JsonNode location = wptResponse.at("/Location");
                final String ilink = wptResponse.get("KeyImageUrl").asText();
                final String desc  = wptResponse.get("Description").asText();

                // For ALCs, waypoints don't have a geocode, of course they have an id (a uuid) though.
                // We artificially create a geocode and a prefix as at least the prefix is used when
                // showing waypoints on the map. It seems that the geocode from the parent is used but
                // prefixed with what we set here. Not clear where the geocode of a waypoint comes into play
                // but we will eventually figure that out.

                wpt.setGeocode(String.valueOf(stageCounter));
                wpt.setPrefix(String.valueOf(stageCounter));

                wpt.setNote("<img style=\"width: 100%;\" src=\"" + ilink + "\"</img><p><p>" + desc + "<p><p>" + wptResponse.get("Question").asText());

                final Geopoint pt = new Geopoint(location.get("Latitude").asDouble(), location.get("Longitude").asDouble());
                if (pt != null && !pt.equals(pointZero)) {
                    wpt.setCoords(pt);
                } else {
                    wpt.setOriginalCoordsEmpty(true);
                }
                if (result == null) {
                    result = new ArrayList<>();
                }

                result.add(wpt);
            } catch (final NullPointerException e) {
                Log.e("_LC LCApi.parseWaypoints", e);
            }
        }
        return result;
    }

    @Nullable
    private static Date parseDate(final String date) {
        try {
            return DATE_FORMAT.parse(date);
        } catch (final ParseException e) {
            return new Date(0);
        }

    }
}

