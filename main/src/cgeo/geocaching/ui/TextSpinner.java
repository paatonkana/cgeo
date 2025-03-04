package cgeo.geocaching.ui;

import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.utils.functions.Action1;
import cgeo.geocaching.utils.functions.Func1;

import android.app.AlertDialog;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Checkable;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a standard CGeo Spinner item where user can select one of multiple values.
 *
 * Supports change of list values.
 *
 * In cgeo, this type of spinner comes in two visual representations: <ul>
 *     <li>As real spinner (eg. selecting coordinate type or image scale)</li>
 *     <li>As text button with self-made dialog on click (e.g. selecting log type)</li>
 *     <li>As text button with onclick-change (selecting next item on click)</li>
 *     <li>As checkable text button (same as text button, but additionally with setchecked option)</li>
 *  *
 * </ul>
 * This class can handle both display cases
 * @param <T>
 */
public class TextSpinner<T> implements AdapterView.OnItemSelectedListener {

    public static final String DISPLAY_VALUE_NULL = "--";

    private final List<T> values = new ArrayList<>();
    private final List<String> displayValues = new ArrayList<>();
    private final Map<T, Integer> valuesToPosition = new HashMap<>();
    private Func1<T, String> displayMapper;
    private Action1<T> changeListener;
    private boolean fireOnChangeOnly;

    private T selectedItem;
    private T previousSelectedItem;

    private Spinner spinner;

    private TextView textView;
    private Func1<T, String> textDisplayMapper;
    private String textDialogTitle;
    private Func1<T, Boolean> setCheckedMapper;
    private boolean textViewClickThroughMode = false;

    public TextSpinner() {
        //initialize lists with dummy value
        setValues(Collections.emptyList());
    }

    /** (Re)Sets the values which are available for selection using this TextSpinner */
    public TextSpinner<T> setValues(@Nullable final List<T> newValues) {

        this.values.clear();
        this.displayValues.clear();
        this.valuesToPosition.clear();
        if (newValues == null || newValues.isEmpty()) {
            this.values.add(null);
            this.valuesToPosition.put(null, 0);
        } else {
            this.values.addAll(newValues);
            int idx = 0;
            for (T v : values) {
                this.valuesToPosition.put(v, idx++);
            }
        }

        recalculateDisplayValues();

        //change selected item if necessary
        if (!valuesToPosition.containsKey(selectedItem)) {
            set(values.get(0));
        } else {
            //FORCE a set. This will set the new item in spinner even if it has changed its position
            set(selectedItem, true);
        }

        return this;
    }


    /** returns current list of values */
    public List<T> getValues() {
        return Collections.unmodifiableList(this.values);
    }

    /** returns current list of DISPLAY values (note: used for unit testing) */
    public List<String> getDisplayValues() {
        return Collections.unmodifiableList(this.displayValues);
    }

    /** for textview: returns display value currently used for showing (note: used for unit testing) */
    public String getTextDisplayValue() {
        return itemToString(get(), true);
    }

     /**
     * (Re)Sets the display mapper, which is used to get a visible representation for all list values.
     * If not set, values are displayed using {@link String#valueOf(Object)}.
     * Mapper will never be called for null values.
     */
    public TextSpinner<T> setDisplayMapper(@Nullable final Func1<T, String> displayMapper) {
        this.displayMapper = displayMapper;
        recalculateDisplayValues();
        return this;
    }

    /** called whenever the selected value changes (by user or programmatically). */
    public TextSpinner<T> setChangeListener(@Nullable final Action1<T> changeListener) {
        return setChangeListener(changeListener, true);
    }

    /**
     * called whenever the selected value changes (by user or programmatically).
     * If fireOnChangeOnly is false, then changelistener will also fire when user selects already selected value again
     */
    public TextSpinner<T> setChangeListener(@Nullable final Action1<T> changeListener, final boolean fireOnChangeOnly) {
        this.changeListener = changeListener;
        this.fireOnChangeOnly = fireOnChangeOnly;
        return this;
    }

    /** if spinner should be represented as a textview, use this method to set the view */
    public TextSpinner<T> setTextView(@NonNull final TextView textView) {
        this.textView = textView;
        this.textView.setOnClickListener(l -> selectTextViewItem());
        return this;
    }

    /** if spinner is be represented as a textview, set title of selection alert dialog */
    public TextSpinner<T> setTextDialogTitle(@Nullable final String textDialogTitle) {
        this.textDialogTitle = textDialogTitle;
        return this;
    }

    /**
     * if spinner is be represented as a textview, set how currently selected value is displayed
     *  If not set, then mapper set with {@link #setDisplayMapper(Func1)} is used
     */
    public TextSpinner<T> setTextDisplayMapper(@Nullable final Func1<T, String> textDisplayMapper) {
        this.textDisplayMapper = textDisplayMapper;
        repaintDisplay();
        return this;
    }

    /**
     * if spinner is be represented as a {@link Checkable} textview (e.g. a {@link android.widget.ToggleButton},
     * set whether checkable is turned on or off dependent on displayed value
     */
    public TextSpinner<T> setCheckedMapper(@Nullable final Func1<T, Boolean> setCheckedMapper) {
        this.setCheckedMapper = setCheckedMapper;
        return this;
    }

    /**
     * if spinner is be represented as a textview, set whether to change item through an alert window (false)
     * or by clicking through them (true)
     */
    public TextSpinner<T> setTextClickThrough(final boolean clickThroughMode) {
        this.textViewClickThroughMode = clickThroughMode;
        return this;
    }

    /** if spinner should be represented by a (GUI) Spinner, set this spinner element here */
    public TextSpinner<T> setSpinner(@NonNull final Spinner spinner) {
        this.spinner = spinner;

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_item, this.displayValues);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.spinner.setAdapter(adapter);
        this.spinner.setOnItemSelectedListener(this);

        return this;
    }

    /** programmatically set currently selected value */
    public TextSpinner<T> set(@Nullable final T value) {
        set(value, false);
        return this;
    }

    /** gets currently selected value */
    public T get() {
        return this.selectedItem;
    }

    private void set(final T value, final boolean force) {
        if (!this.valuesToPosition.containsKey(value)) {
            return;
        }
        if (force || !Objects.equals(previousSelectedItem, value)) {
            this.selectedItem = value;
            repaintDisplay();
        }
        if (this.changeListener != null && (!this.fireOnChangeOnly || !Objects.equals(previousSelectedItem, value))) {
            this.changeListener.call(selectedItem);
        }
        this.previousSelectedItem = this.selectedItem;
    }

    private void repaintDisplay() {

        if (spinner != null && !this.values.isEmpty()) {
            setChecked(spinner);
            spinner.setSelection(getPositionFor(this.selectedItem, 0));
        }
        if (textView != null) {
            setChecked(textView);
            textView.setText(itemToString(this.selectedItem, true));
        }
    }

    private void setChecked(final View view) {
        if (view instanceof Checkable && this.setCheckedMapper != null) {
            ((Checkable) view).setChecked(this.setCheckedMapper.call(this.selectedItem));
        }
    }

    private void recalculateDisplayValues() {
        //optimization: use existing list as much as possible
        if (this.values.size() < this.displayValues.size()) {
            this.displayValues.clear();
        }
        int idx = 0;
        for (T v : this.values) {
            if (idx < this.displayValues.size()) {
                this.displayValues.set(idx++, itemToString(v, false));
            } else {
                this.displayValues.add(itemToString(v, false));
                idx++;
            }
        }
        if (this.spinner != null) {
            //spinner adapter needs to e notified when its data set as changed, otherwise GUI is not updated
            ((ArrayAdapter<String>) this.spinner.getAdapter()).notifyDataSetChanged();
        }
    }

    private String itemToString(final T item, final boolean useTextDisplayMapper) {
        if (item == null) {
            return DISPLAY_VALUE_NULL;
        }
        final Func1<T, String> mapper = (useTextDisplayMapper && this.textDisplayMapper != null) ? this.textDisplayMapper : this.displayMapper;
        return mapper == null ? String.valueOf(item) : mapper.call(item);
    }



    //for Spinner-view: called when element changes
    @Override
    public void onItemSelected(final AdapterView<?> parent, final View view, final int pos, final long id) {
        set(values.get(pos));
    }

    //for Spinner
    @Override
    public void onNothingSelected(final AdapterView<?> adapterView) {
        //empty
    }

    private int getPositionFor(final T value, final int defaultValue) {
        if (valuesToPosition.containsKey(value)) {
            return valuesToPosition.get(value);
        }
        return defaultValue;
    }

    /** displays data for selection in alert dialog. Used for textview-representation */
    private void selectTextViewItem() {

        if (this.textViewClickThroughMode) {
            final int pos = getPositionFor(this.selectedItem, 0);
            final int newPos = (pos + 1) % this.values.size();
            set(values.get(newPos));
        } else {

            final AlertDialog.Builder alert = Dialogs.newBuilder(textView.getContext());
            if (this.textDialogTitle != null) {
                alert.setTitle(this.textDialogTitle);
            }

            alert.setSingleChoiceItems(this.displayValues.toArray(new String[0]), getPositionFor(selectedItem, -1), (dialog, pos) -> {
                set(values.get(pos));
                dialog.dismiss();
            });
            alert.create().show();
        }
    }

}
