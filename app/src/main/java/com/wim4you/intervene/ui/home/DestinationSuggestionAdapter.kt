package com.wim4you.intervene.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import com.wim4you.intervene.R
import com.wim4you.intervene.repository.DestinationSuggestion

class DestinationSuggestionAdapter(
  context: Context,
) : ArrayAdapter<DestinationSuggestion>(context, R.layout.item_destination_suggestion) {

  private var suggestions: List<DestinationSuggestion> = emptyList()

  fun submitSuggestions(newSuggestions: List<DestinationSuggestion>) {
    suggestions = newSuggestions
    clear()
    addAll(newSuggestions)
    notifyDataSetChanged()
  }

  override fun getFilter(): Filter = PassthroughFilter()

  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val view = convertView ?: LayoutInflater.from(context)
      .inflate(R.layout.item_destination_suggestion, parent, false)

    val suggestion = getItem(position) ?: return view
    view.findViewById<TextView>(R.id.suggestionAddress).text = suggestion.address
    view.findViewById<TextView>(R.id.suggestionUsedAt).text =
      context.getString(R.string.route_destination_last_used, suggestion.usedAtLabel)

    return view
  }

  private inner class PassthroughFilter : Filter() {
    override fun performFiltering(constraint: CharSequence?): FilterResults {
      return FilterResults().apply {
        values = suggestions
        count = suggestions.size
      }
    }

    @Suppress("UNCHECKED_CAST")
    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
      val items = results?.values as? List<DestinationSuggestion> ?: emptyList()
      clear()
      addAll(items)
      notifyDataSetChanged()
    }
  }
}
