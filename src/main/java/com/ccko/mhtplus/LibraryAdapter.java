package com.ccko.mhtplus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VH> {

	public boolean isSelectionMode() {
		return selectionMode;
	}

	public interface Callback {
		void onOpen(LibraryEntry entry);

		void onSelectionChanged(int count);

		void onLongPress(LibraryEntry entry);
	}

	private final List<LibraryEntry> items = new ArrayList<>();
	private final Callback callback;

	// Selection state
	private final Set<String> selectedIds = new HashSet<>(); // use folderName as stable id
	private boolean selectionMode = false;

	public LibraryAdapter(Callback callback) {
		this.callback = callback;
	}

	public List<LibraryEntry> getItems() {
		return items;
	}

	public void setItems(List<LibraryEntry> list) {
		items.clear();
		if (list != null)
			items.addAll(list);
		selectedIds.clear();
		selectionMode = false;
		notifyDataSetChanged();
		if (callback != null)
			callback.onSelectionChanged(0);
	}

	public void enterSelectionMode(boolean enter) {
		selectionMode = enter;
		if (!enter)
			selectedIds.clear();
		notifyDataSetChanged();
		if (callback != null)
			callback.onSelectionChanged(selectedIds.size());
	}

	public void toggleSelection(LibraryEntry e) {
		String id = e.folderName;
		if (selectedIds.contains(id))
			selectedIds.remove(id);
		else
			selectedIds.add(id);
		selectionMode = !selectedIds.isEmpty();
		notifyDataSetChanged();
		if (callback != null)
			callback.onSelectionChanged(selectedIds.size());
	}

	public void selectById(String id, boolean select) {
		if (select)
			selectedIds.add(id);
		else
			selectedIds.remove(id);
		selectionMode = !selectedIds.isEmpty();
		notifyDataSetChanged();
		if (callback != null)
			callback.onSelectionChanged(selectedIds.size());
	}

	public List<LibraryEntry> getSelectedItems() {
		List<LibraryEntry> out = new ArrayList<>();
		for (LibraryEntry e : items)
			if (selectedIds.contains(e.folderName))
				out.add(e);
		return out;
	}

	@NonNull
	@Override
	public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_library_row, parent, false);
		return new VH(v);
	}

	@Override
	public void onBindViewHolder(@NonNull VH holder, int position) {
		final LibraryEntry e = items.get(position);

		String title = (e.title == null || e.title.isEmpty()) ? e.folderName : e.title;
		holder.tv1.setText(title);

		String subtitle = DateFormat.getDateTimeInstance().format(new Date(e.timestamp));
		holder.tv2.setText(subtitle);

		// Selection UI
		holder.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
		boolean checked = selectedIds.contains(e.folderName);
		holder.checkbox.setChecked(checked);

		holder.itemView.setActivated(checked);
		holder.itemView.setBackgroundColor(checked ? 0x2200FF00 : 0x00000000);

		holder.itemView.setOnClickListener(v -> {
			if (selectionMode) {
				toggleSelection(e);
			} else {
				if (callback != null)
					callback.onOpen(e);
			}
		});

		holder.itemView.setOnLongClickListener(v -> {
			if (!selectionMode) {
				enterSelectionMode(true);
				toggleSelection(e);
				if (callback != null)
					callback.onLongPress(e);
				return true;
			}
			toggleSelection(e);
			return true;
		});

		holder.checkbox.setOnClickListener(v -> toggleSelection(e));

		// Icon depending on type
		if (e.savedPath != null && e.savedPath.startsWith("content://")) {
			holder.icon.setImageResource(android.R.drawable.ic_menu_save);
		} else {
			holder.icon.setImageResource(android.R.drawable.ic_menu_view);
		}
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	static class VH extends RecyclerView.ViewHolder {
		TextView tv1;
		TextView tv2;
		CheckBox checkbox;
		ImageView icon;

		VH(@NonNull View itemView) {
			super(itemView);
			checkbox = itemView.findViewById(R.id.checkbox_select);
			icon = itemView.findViewById(R.id.icon_item);
			tv1 = itemView.findViewById(R.id.title);
			tv2 = itemView.findViewById(R.id.subtitle);
		}
	}
}
