package com.convoy.androidtranscriber;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

public class ModelListAdapter extends ArrayAdapter<ManageModelsActivity.ModelRow> {
    public interface RowActionListener {
        void onRowAction(ManageModelsActivity.ModelRow row);
    }

    private final RowActionListener listener;

    public ModelListAdapter(@NonNull Context context, @NonNull List<ManageModelsActivity.ModelRow> items,
                            @NonNull RowActionListener listener) {
        super(context, 0, items);
        this.listener = listener;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_model_entry, parent, false);
        }

        ManageModelsActivity.ModelRow row = getItem(position);
        if (row != null) {
            TextView name = view.findViewById(R.id.tvModelName);
            TextView meta = view.findViewById(R.id.tvModelMeta);
            Button action = view.findViewById(R.id.btnModelAction);
            name.setText(row.displayName);
            meta.setText(row.statusLine());
            action.setText(row.actionLabel);
            action.setEnabled(row.actionEnabled);
            action.setOnClickListener(v -> listener.onRowAction(row));
        }
        return view;
    }
}
