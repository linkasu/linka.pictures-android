package su.linka.pictures.activity;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import su.linka.pictures.R;

public class GridSettingsView extends LinearLayout {
    private final LayoutInflater layoutInflater;
    private final Switch isOutputSwitch;
    private final Switch isPageButtonsSwitch;
    private GridSettings settings;

    public GridSettingsView(Context context) {
        super(context);

        layoutInflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.grid_settings, this, true);

       isOutputSwitch = findViewById(R.id.is_output_switch);
       isPageButtonsSwitch = findViewById(R.id.is_page_buttons_switch);
    }

    public void setSettings(GridSettings settings) {
        this.settings = settings;
        isOutputSwitch.setChecked(settings.isOutput);
        isPageButtonsSwitch.setChecked(settings.isPagesButtons);
    }

    public void commit() {
        settings.isOutput = isOutputSwitch.isChecked();
        settings.isPagesButtons = isPageButtonsSwitch.isChecked();
    }
}
