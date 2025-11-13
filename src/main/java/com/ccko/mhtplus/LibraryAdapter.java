package com.ccko.mhtplus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VH> {

	public interface Callback {
		void onOpen(LibraryEntry entry);

		void onDelete(LibraryEntry entry);
	}

	private final List<LibraryEntry> items = new ArrayList<>();
	private final Callback callback;

	public LibraryAdapter(Callback callback) {
		this.callback = callback;
	}

	public void setItems(List<LibraryEntry> list) {
		items.clear();
		if (list != null)
			items.addAll(list);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
		// We'll add a delete button programmatically to the item view
		return new VH(v);
	}

	@Override
	public void onBindViewHolder(@NonNull VH holder, int position) {
		final LibraryEntry e = items.get(position);
		String title = (e.title == null || e.title.isEmpty()) ? e.folderName : e.title;
		holder.tv1.setText(title);
		String subtitle = e.url == null || e.url.isEmpty()
				? DateFormat.getDateTimeInstance().format(new Date(e.timestamp))
				: e.url + " · " + DateFormat.getDateTimeInstance().format(new Date(e.timestamp));
		holder.tv2.setText(subtitle);

		holder.itemView.setOnClickListener(v -> {
			if (callback != null)
				callback.onOpen(e);
		});

		holder.btnDelete.setOnClickListener(v -> {
			if (callback != null)
				callback.onDelete(e);
		});
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	static class VH extends RecyclerView.ViewHolder {
		TextView tv1;
		TextView tv2;
		ImageButton btnDelete;

		VH(@NonNull View itemView) {
			super(itemView);
			tv1 = itemView.findViewById(android.R.id.text1);
			tv2 = itemView.findViewById(android.R.id.text2);
			// Add delete button to the right
			btnDelete = new ImageButton(itemView.getContext());
			btnDelete.setImageResource(android.R.drawable.ic_menu_delete);
			btnDelete.setBackground(null);
			int size = (int) (itemView.getResources().getDisplayMetrics().density * 40);
			btnDelete.setLayoutParams(new ViewGroup.LayoutParams(size, size));
			((ViewGroup) itemView).addView(btnDelete);
		}
	}
}
