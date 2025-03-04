package cgeo.geocaching.models;

import cgeo.geocaching.calculator.CalcStateEvaluator;
import cgeo.geocaching.calculator.CoordinatesCalculateUtils;
import cgeo.geocaching.calculator.FormulaParser;
import cgeo.geocaching.calculator.FormulaWrapper;
import cgeo.geocaching.calculator.VariableData;
import cgeo.geocaching.enumerations.WaypointType;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.location.GeopointFormatter;
import cgeo.geocaching.location.GeopointParser;
import cgeo.geocaching.location.GeopointWrapper;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.utils.TextUtils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jetbrains.annotations.NotNull;

public class WaypointParser {

    public static final String PARSING_COORD_FORMULA_PLAIN = "(F-PLAIN)";

    //Constants for waypoint parsing
    private static final String PARSING_NAME_PRAEFIX = "@";
    private static final char PARSING_USERNOTE_DELIM = '"';
    private static final char PARSING_USERNOTE_ESCAPE = '\\';
    private static final String PARSING_USERNOTE_CONTINUED = "...";
    private static final String PARSING_PREFIX_OPEN = "[";
    private static final String PARSING_PREFIX_CLOSE = "]";
    private static final String PARSING_TYPE_OPEN = "(";
    private static final String PARSING_TYPE_CLOSE = ")";
    private static final String PARSING_COORD_EMPTY = "(NO-COORD)";
    private static final String BACKUP_TAG_OPEN = "{c:geo-start}";
    private static final String BACKUP_TAG_CLOSE = "{c:geo-end}";

    private Collection<Waypoint> waypoints;
    private final String namePrefix;
    private int count;

    /**
     * Detect coordinates in the given text and converts them to user-defined waypoints.
     * Works by rule of thumb.
     *
     * @param namePrefix Prefix of the name of the waypoint
     */
    public WaypointParser(@NonNull final String namePrefix) {
        this.namePrefix = namePrefix;
    }

    /**
     * Detect coordinates in the given text and converts them to user-defined waypoints.
     * Works by rule of thumb.
     *
     * @param text       Text to parse for waypoints
     * @return a collection of found waypoints
     */
    public Collection<Waypoint> parseWaypoints(@NonNull final String text) {
        this.count = 1;
        if (null == waypoints) {
            waypoints = new LinkedList<>();
        } else {
            waypoints.clear();
        }

        //if a backup is found, we parse it first
        for (final String backup : TextUtils.getAll(text, BACKUP_TAG_OPEN, BACKUP_TAG_CLOSE)) {
            parseWaypointsFromString(backup);
        }
        parseWaypointsFromString(TextUtils.replaceAll(text, BACKUP_TAG_OPEN, BACKUP_TAG_CLOSE, ""));

        return waypoints;
    }

    private void parseWaypointsFromString(final String text) {
        // search waypoints with coordinates
        parseWaypointsWithCoords(text);

        // search waypoints with empty coordinates
        parseWaypointsWithSpecificCoords(text, PARSING_COORD_EMPTY, null);

        // search waypoints with formula
        parseWaypointsWithSpecificCoords(text, PARSING_COORD_FORMULA_PLAIN, Settings.CoordInputFormatEnum.Plain);
    }

    private void parseWaypointsWithCoords(final String text) {
        final Collection<GeopointWrapper> matches = GeopointParser.parseAll(text);
        for (final GeopointWrapper match : matches) {
            final Waypoint wp = parseSingleWaypoint(match, null);
            if (wp != null) {
                waypoints.add(wp);
                count++;
            }
        }
    }

    private void parseWaypointsWithSpecificCoords(final String text, final String parsingCoord, final Settings.CoordInputFormatEnum coordFormat) {
        int idxWaypoint = text.indexOf(parsingCoord);

        while (idxWaypoint >= 0) {
            final GeopointWrapper match = new GeopointWrapper(null, idxWaypoint, parsingCoord.length(), text);
            final Waypoint wp = parseSingleWaypoint(match, coordFormat);
            if (wp != null) {
                waypoints.add(wp);
                count++;
            }
            idxWaypoint = text.indexOf(parsingCoord, idxWaypoint + parsingCoord.length());
        }
    }

    private Waypoint parseSingleWaypoint(final GeopointWrapper match, final Settings.CoordInputFormatEnum coordFormat) {
        final Geopoint point = match.getGeopoint();
        final Integer start = match.getStart();
        final Integer end = match.getEnd();
        final String text = match.getText();

        final String[] wordsBefore = TextUtils.getWords(TextUtils.getTextBeforeIndexUntil(text, start, "\n"));
        final String lastWordBefore = wordsBefore.length == 0 ? "" : wordsBefore[wordsBefore.length - 1];

        //try to get a waypointType
        final WaypointType wpType = parseWaypointType(text.substring(Math.max(0, start - 20), start), lastWordBefore);

        //try to get a name and a prefix
        final ImmutablePair<String, String> parsedNameAndPrefix = parseNameAndPrefix(wordsBefore, wpType);
        String name = parsedNameAndPrefix.getLeft();
        final String prefix = parsedNameAndPrefix.getRight();
        if (StringUtils.isBlank(name)) {
            name = namePrefix + " " + count;
        }

        //create the waypoint
        final Waypoint waypoint = new Waypoint(name, wpType, true);
        waypoint.setCoords(point);
        waypoint.setPrefix(prefix);

        String afterCoords = TextUtils.getTextAfterIndexUntil(text, end - 1, null);

        if (null != coordFormat) {
            // try to get a formula
            final ImmutablePair<CalcState, String> coordFormula = parseFormula(afterCoords, coordFormat);
            final CalcState calcState = coordFormula.left;
            if (null != calcState) {
                waypoint.setCalcStateJson(calcState.toJSON().toString());
                // try to evaluate valid coordinates
                final CalcStateEvaluator eval = new CalcStateEvaluator(calcState.equations, calcState.freeVariables, null);
                final Geopoint gp = eval.evaluate(calcState.plainLat, calcState.plainLon);
                waypoint.setCoords(gp);
            }

            afterCoords = coordFormula.right;
        }

        //try to get a user note
        final String userNote = parseUserNote(afterCoords, 0);
        if (!StringUtils.isBlank(userNote)) {
            waypoint.setUserNote(userNote.trim());
        }

        return waypoint;
    }

    private String parseUserNote(final String text, final int end) {
        final String after = TextUtils.getTextAfterIndexUntil(text, end - 1, null).trim();
        if (after.startsWith("" + PARSING_USERNOTE_DELIM)) {
            return TextUtils.parseNextDelimitedValue(after, PARSING_USERNOTE_DELIM, PARSING_USERNOTE_ESCAPE);
        }
        return TextUtils.getTextAfterIndexUntil(text, end - 1, "\n");
    }

    /**
     * try to parse a name out of given words. If not possible, empty is returned
     */
    @NotNull
    private ImmutablePair<String, String> parseNameAndPrefix(final String[] words, final WaypointType wpType) {
        if (words.length == 0 || !words[0].startsWith(PARSING_NAME_PRAEFIX)) {
            return new ImmutablePair<>("", "");
        }
        //first word handling
        String name = words[0].substring(PARSING_NAME_PRAEFIX.length());
        String prefix = "";
        final int idx = name.indexOf(PARSING_PREFIX_CLOSE);
        if (idx > 0 && name.startsWith(PARSING_PREFIX_OPEN)) {
            prefix = name.substring(PARSING_PREFIX_OPEN.length(), idx).trim();
            name = name.substring(idx + 1);
        }

        //handle additional words if any
        for (int i = 1; i < words.length; i++) {
            if (useWordForParsedName(words[i], i == words.length - 1, wpType)) {
                if (name.length() > 0) {
                    name += " ";
                }
                name += words[i];
            }
        }
        return new ImmutablePair<>(StringUtils.isBlank(name) ? "" : name.trim(), prefix);
    }

    private boolean useWordForParsedName(final String word, final boolean isLast, final WaypointType wpType) {
        return
            (!StringUtils.isBlank(word)) &&
                //remove words which are in parenthesis (is usually the waypoint type)
                !(word.startsWith(PARSING_TYPE_OPEN) && word.endsWith(PARSING_TYPE_CLOSE)) &&
                //remove last word if it is just the waypoint type id
                !(isLast && word.toLowerCase(Locale.getDefault()).equals(wpType.getShortId().toLowerCase(Locale.getDefault())));
    }

    /**
     * Detect waypoint types in the personal note text. Tries to find various ways that
     * the waypoints name or id is written in given text.
     */
    @SuppressWarnings("PMD.NPathComplexity") // method readability will not improve by splitting it up or using lambda-expressions
    private WaypointType parseWaypointType(final String input, final String lastWord) {
        final String lowerInput = input.toLowerCase(Locale.getDefault());
        final String lowerLastWord = lastWord.toLowerCase(Locale.getDefault());

        //find the LAST (if any) enclosed one-letter-word in the input
        String enclosedShortIdCandidate = null;
        final int lastClosingIdx = lowerInput.lastIndexOf(PARSING_TYPE_CLOSE);
        if (lastClosingIdx > 0) {
            final int lastOpeningIdx = lowerInput.lastIndexOf(PARSING_TYPE_OPEN, lastClosingIdx);
            if (lastOpeningIdx >= 0 && lastOpeningIdx + PARSING_TYPE_OPEN.length() + 1 == lastClosingIdx) {
                enclosedShortIdCandidate = lowerInput.substring(lastClosingIdx - 1, lastClosingIdx);
            }
        }

        for (final WaypointType wpType : WaypointType.ALL_TYPES) {
            final String lowerShortId = wpType.getShortId().toLowerCase(Locale.getDefault());
            if (lowerLastWord.equals(lowerShortId) || lowerLastWord.contains(PARSING_TYPE_OPEN + lowerShortId + PARSING_TYPE_CLOSE)) {
                return wpType;
            }
        }
        if (enclosedShortIdCandidate != null) {
            for (final WaypointType wpType : WaypointType.ALL_TYPES) {
                if (enclosedShortIdCandidate.equals(wpType.getShortId().toLowerCase(Locale.getDefault()))) {
                    return wpType;
                }
            }
        }
        for (final WaypointType wpType : WaypointType.ALL_TYPES) {
            // check the old, longer waypoint names first (to not interfere with the shortened versions)
            if (lowerInput.contains(wpType.getL10n().toLowerCase(Locale.getDefault()))) {
                return wpType;
            }
        }
        for (final WaypointType wpType : WaypointType.ALL_TYPES) {
            // then check the new, shortened versions
            if (lowerInput.contains(wpType.getNameForNewWaypoint().toLowerCase(Locale.getDefault()))) {
                return wpType;
            }
        }
        for (final WaypointType wpType : WaypointType.ALL_TYPES) {
            if (lowerInput.contains(wpType.id)) {
                return wpType;
            }
        }
        for (final WaypointType wpType : WaypointType.ALL_TYPES) {
            if (lowerInput.contains(wpType.name().toLowerCase(Locale.US))) {
                return wpType;
            }
        }
        return WaypointType.WAYPOINT;
    }

    /**
     * try to parse a name out of given words. If not possible, null is returned
     */
    @NotNull
    private ImmutablePair<CalcState, String> parseFormula(final String text, final Settings.CoordInputFormatEnum formulaFormat) {
        try {
            final FormulaParser formulaParser = new FormulaParser(formulaFormat);
            final FormulaWrapper parsedFullCoordinates = formulaParser.parse(text);
            if (null != parsedFullCoordinates) {
                final String latText = parsedFullCoordinates.getFormulaLat();
                final String lonText = parsedFullCoordinates.getFormulaLon();
                final List<VariableData> variables = new ArrayList<>();

                // all text after the formula
                String remainingString = parsedFullCoordinates.getText().substring(parsedFullCoordinates.getEnd()).trim();

                final String[] formulaList = remainingString.split(FormulaParser.WPC_DELIM_PATTERN_STRING);
                for (final String varText : formulaList
                ) {
                    boolean removeDelimiter = varText.isEmpty();
                    final String[] equations = varText.split("=", -1);
                    if (1 <= equations.length) {
                        final String varName = equations[0].trim();
                        if (1 == varName.length()) {
                            removeDelimiter = true;
                            final String varExpression = equations[1].trim();
                            if (!varExpression.isEmpty()) {
                                variables.add(new VariableData(varName.charAt(0), varExpression));
                            }
                        }
                    }
                    if (removeDelimiter) {
                        final int idxWpcDelim = remainingString.indexOf(FormulaParser.WPC_DELIM);
                        remainingString = remainingString.substring(idxWpcDelim + 1);
                    } else {
                        break;
                    }
                }
                final CalcState calcState = CoordinatesCalculateUtils.createCalcState(latText, lonText, variables);

                return new ImmutablePair<>(calcState, remainingString);
            }
        } catch (final FormulaParser.ParseException ignored) {
            // no formula
        }

        return new ImmutablePair<>(null, text);
    }

    public static String removeParseableWaypointsFromText(final String text) {
        return TextUtils.replaceAll(text, BACKUP_TAG_OPEN, BACKUP_TAG_CLOSE, "").trim();
    }

    /**
     * Replaces waypoints stored in text with the ones passed as parameter.
     *
     * @param text      text to search and replace waypoints in
     * @param waypoints new waypoints to store
     * @param maxSize   if >0 then total size of returned text may not exceed this parameter
     * @return new text, or null if waypoints could not be placed due to size restrictions
     */
    public static String putParseableWaypointsInText(final String text, final Collection<Waypoint> waypoints, final int maxSize) {
        final String cleanText = removeParseableWaypointsFromText(text) + "\n\n";
        if (maxSize > -1 && cleanText.length() > maxSize) {
            return null;
        }
        final String newWaypoints = getParseableText(waypoints, maxSize - cleanText.length(), true);
        if (newWaypoints == null) {
            return null;
        }
        return cleanText + newWaypoints;
    }

    /**
     * Tries to create a parseable text containing all  information from given waypoints
     * and meeting a given maximum text size. Different strategies are applied to meet
     * that text size.
     * if 'includeBackupTags' is set, then returned text is surrounded by tags
     *
     * @return parseable text for wayppints, or null if maxsize cannot be met
     */
    public static String getParseableText(final Collection<Waypoint> waypoints, final int maxSize, final boolean includeBackupTags) {
        String text = getParseableTextWithRestrictedUserNote(waypoints, -1, includeBackupTags);
        if (maxSize < 0 || text.length() <= maxSize) {
            return text;
        }

        //try to shrink size by reducing maximum user note length
        for (int maxUserNoteLength = 50; maxUserNoteLength >= 0; maxUserNoteLength -= 10) {
            text = getParseableTextWithRestrictedUserNote(waypoints, maxUserNoteLength, includeBackupTags);
            if (text.length() <= maxSize) {
                return text;
            }
        }

        //not possible to meet size requirements
        return null;
    }

    public static String getParseableTextWithRestrictedUserNote(final Collection<Waypoint> waypoints, final int maxUserNoteSize, final boolean includeBackupTags) {
        //no streaming allowed
        final List<String> waypointsAsStrings = new ArrayList<>();
        for (final Waypoint wp : waypoints) {
            waypointsAsStrings.add(getParseableText(wp, maxUserNoteSize));
        }
        return (includeBackupTags ? BACKUP_TAG_OPEN + "\n" : "") +
            StringUtils.join(waypointsAsStrings, "\n") +
            (includeBackupTags ? "\n" + BACKUP_TAG_CLOSE : "");
    }

    /**
     * creates parseable waypoint text
     *
     * @param maxUserNoteSize if -1, user notes size is not limited. if 0, user note is omitted.
     *                        if >0 user note size is limited to given size
     * @return parseable waypoint text
     */
    public static String getParseableText(final Waypoint wp, final int maxUserNoteSize) {
        final StringBuilder sb = new StringBuilder();
        //name
        sb.append(PARSING_NAME_PRAEFIX);
        if (!wp.isUserDefined()) {
            sb.append(PARSING_PREFIX_OPEN).append(wp.getPrefix()).append(PARSING_PREFIX_CLOSE);
        }
        sb.append(wp.getName()).append(" ");

        //type
        sb.append(PARSING_TYPE_OPEN).append(wp.getWaypointType().getShortId().toUpperCase(Locale.US))
            .append(PARSING_TYPE_CLOSE).append(" ");
        //coordinate
        if (wp.getCoords() == null) {
            final String calcStateJson = wp.getCalcStateJson();
            if (null != calcStateJson) {
                sb.append(getParseableFormula(wp));
            } else {
                sb.append(PARSING_COORD_EMPTY);
            }
        } else {
            sb.append(wp.getCoords().format(GeopointFormatter.Format.LAT_LON_DECMINUTE_SHORT_RAW));
        }

        //user note
        String userNote = wp.getUserNote();
        if (maxUserNoteSize != 0 && !StringUtils.isBlank(userNote)) {
            if (maxUserNoteSize > 0 && userNote.length() > maxUserNoteSize) {
                userNote = userNote.substring(0, maxUserNoteSize) + PARSING_USERNOTE_CONTINUED;
            }
            //if user note contains itself newlines, then start user note on a second line
            sb.append(userNote.contains("\n") ? "\n" : " ");
            sb.append(TextUtils.createDelimitedValue(userNote, PARSING_USERNOTE_DELIM, PARSING_USERNOTE_ESCAPE));
        }

        return sb.toString();
    }

    public static String getParseableFormula(final Waypoint wp) {
        final StringBuilder sb = new StringBuilder();

        final String calcStateJson = wp.getCalcStateJson();
        if (null != calcStateJson) {
            final CalcState calcState = CalcState.fromJSON(calcStateJson);

            final String formulaString = getParseableFormulaString(calcState);
            if (!formulaString.isEmpty()) {
                sb.append(formulaString);
                final String variableString = getParseableVariablesString(calcState);
                if (!variableString.isEmpty()) {
                    sb.append(FormulaParser.WPC_DELIM + variableString);
                }
            }
        }
        return sb.toString();
    }

    private static String getParseableFormulaString(final CalcState calcState) {
        final StringBuilder sb = new StringBuilder();
        if (calcState.format == Settings.CoordInputFormatEnum.Plain) {
            sb.append(PARSING_COORD_FORMULA_PLAIN + " ");
            sb.append(calcState.plainLat + " " + calcState.plainLon + " ");
        }
        return sb.toString();
    }

    private static String getParseableVariablesString(final CalcState calcState) {
        final StringBuilder sb = new StringBuilder();
        for (VariableData equ : calcState.equations) {
            final String equExpr = equ.getExpression().trim();
            if (!equExpr.isEmpty()) {
                sb.append(equ.getName() + "=" + equExpr + FormulaParser.WPC_DELIM);
            }
        }
        for (VariableData var : calcState.freeVariables) {
            final String varExpr = var.getExpression().trim();
            if (!varExpr.isEmpty()) {
                sb.append(var.getName() + "=" + varExpr + FormulaParser.WPC_DELIM);
            }
        }
        return sb.toString();
    }
}
