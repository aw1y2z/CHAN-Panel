package io.github.aw1y2z.changan.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.data.ControlAction

/**
 * 控车页的一个格子。两种类型：
 *  - 功能块（[action] 非空），宽度可调 1/4 · 1/3 · 1/2
 *  - 空调卡片（[action] 为空），固定整行，但同样参与拖拽排序
 */
data class ControlTile(
    val action: ControlAction?,
    var span: Int = ControlTileAdapter.SPAN_QUARTER,
) {
    val isAir: Boolean get() = action == null
}

class ControlTileAdapter(
    private val tiles: MutableList<ControlTile>,
    private val isUsable: (ControlAction) -> Boolean,
    private val onClick: (ControlAction) -> Unit,
    private val onResize: (ControlTile) -> Unit,
    private val onExitEdit: () -> Unit,
    private val onAirTemp: (Int) -> Unit,
    private val onAirOn: () -> Unit,
    private val onAirOff: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // 关键：用 payload 局部刷新。
            // 如果这里 notifyDataSetChanged()，会把 ItemTouchHelper 正在拖拽的 item 也重建，
            // 结果就是「长按能进编辑态，但一拖就断」。
            notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT)
        }

    var busyAction: ControlAction? = null

    /** 空调卡片当前显示的温度 / 状态 / 是否可用，由 Activity 驱动 */
    var airTempText: String = "22°C"
        set(value) {
            field = value
            refreshAir()
        }

    var airStatusText: String = "空调 --"
        set(value) {
            field = value
            refreshAir()
        }

    var airUsable: Boolean = true
        set(value) {
            field = value
            refreshAir()
        }

    private fun refreshAir() {
        val pos = tiles.indexOfFirst { it.isAir }
        if (pos >= 0) notifyItemChanged(pos)
    }

    inner class TileVH(view: View) : RecyclerView.ViewHolder(view) {
        val body: View = view.findViewById(R.id.tileBody)
        val icon: ImageView = view.findViewById(R.id.ivTile)
        val label: TextView = view.findViewById(R.id.tvTile)
        val resize: ImageView = view.findViewById(R.id.btnResize)
    }

    inner class AirVH(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.airCard)
        val temp: TextView = view.findViewById(R.id.tvAcTemp)
        val now: TextView = view.findViewById(R.id.tvAcNow)
        val minus: TextView = view.findViewById(R.id.tvAcMinus)
        val plus: TextView = view.findViewById(R.id.tvAcPlus)
        val apply: TextView = view.findViewById(R.id.tvAcApply)
        val off: TextView = view.findViewById(R.id.tvAcOff)
    }

    override fun getItemViewType(position: Int): Int =
        if (tiles[position].isAir) VT_AIR else VT_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VT_AIR) {
            AirVH(inflater.inflate(R.layout.item_control_air, parent, false))
        } else {
            TileVH(inflater.inflate(R.layout.item_control_tile, parent, false))
        }
    }

    override fun getItemCount(): Int = tiles.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val tile = tiles[position]
        if (holder is AirVH) bindAir(holder) else bindTile(holder as TileVH, tile)
    }

    /** 编辑态切换只更新按钮可见性，不动其它内容 */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads[0] == PAYLOAD_EDIT) {
            if (holder is TileVH) {
                holder.resize.visibility = if (editing) View.VISIBLE else View.GONE
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindTile(holder: TileVH, tile: ControlTile) {
        val action = tile.action ?: return
        val ctx = holder.itemView.context

        holder.icon.setImageResource(iconOf(action))
        holder.icon.imageTintList = ColorStateList.valueOf(ctx.getColor(tintOf(action)))
        holder.label.text = shortLabelOf(action)

        holder.body.alpha = when {
            !isUsable(action) -> 0.3f
            busyAction == action -> 0.45f
            else -> 1f
        }

        holder.resize.visibility = if (editing) View.VISIBLE else View.GONE
        holder.resize.setOnClickListener { onResize(tile) }

        holder.body.setOnClickListener {
            if (editing) onExitEdit() else onClick(action)
        }
    }

    private fun bindAir(holder: AirVH) {
        holder.temp.text = airTempText
        holder.now.text = airStatusText
        holder.apply.alpha = if (airUsable) 1f else 0.3f
        holder.off.alpha = if (airUsable) 1f else 0.3f

        holder.minus.setOnClickListener { if (!editing) onAirTemp(-1) else onExitEdit() }
        holder.plus.setOnClickListener { if (!editing) onAirTemp(1) else onExitEdit() }
        holder.apply.setOnClickListener { if (!editing) onAirOn() else onExitEdit() }
        holder.off.setOnClickListener { if (!editing) onAirOff() else onExitEdit() }
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val item = tiles.removeAt(from)
        tiles.add(to, item)
        notifyItemMoved(from, to)
    }

    /** 功能块宽度循环：1/4 → 1/3 → 1/2 → 1/4 */
    fun cycleSpan(tile: ControlTile): Int {
        tile.span = when (tile.span) {
            SPAN_QUARTER -> SPAN_THIRD
            SPAN_THIRD -> SPAN_HALF
            else -> SPAN_QUARTER
        }
        val pos = tiles.indexOf(tile)
        if (pos >= 0) notifyItemChanged(pos)
        return tile.span
    }

    fun currentOrder(): List<ControlTile> = tiles.toList()

    fun refreshUsable() = notifyDataSetChanged()

    /** 找到空调卡片的「开 / 关」视图，供执行中置灰用 */
    fun airView(open: Boolean): View? {
        val pos = tiles.indexOfFirst { it.isAir }
        if (pos < 0) return null
        val holder = rvRef?.findViewHolderForAdapterPosition(pos) as? AirVH ?: return null
        return if (open) holder.apply else holder.off
    }

    /** 由 Activity 注入 RecyclerView 引用，便于定位空调 item */
    var rvRef: RecyclerView? = null

    companion object {
        const val SPAN_QUARTER = 3
        const val SPAN_THIRD = 4
        const val SPAN_HALF = 6
        /** 空调固定整行（12 列） */
        const val SPAN_FULL = 12

        private const val VT_TILE = 0
        private const val VT_AIR = 1
        private const val PAYLOAD_EDIT = "edit"

        fun spanLabel(span: Int): String = when (span) {
            SPAN_QUARTER -> "1/4 宽"
            SPAN_THIRD -> "1/3 宽"
            SPAN_HALF -> "1/2 宽"
            else -> "整行"
        }

        fun iconOf(action: ControlAction): Int = when (action) {
            ControlAction.LOCK -> R.drawable.ic_c_lock
            ControlAction.UNLOCK -> R.drawable.ic_c_unlock
            ControlAction.REMOTE_START, ControlAction.REMOTE_STOP -> R.drawable.ic_c_power
            ControlAction.OPEN_WINDOWS,
            ControlAction.CLOSE_WINDOWS,
            ControlAction.WINDOW_SLIT,
            -> R.drawable.ic_c_window
            ControlAction.OPEN_SUNROOF, ControlAction.CLOSE_SUNROOF -> R.drawable.ic_c_roof
            ControlAction.OPEN_TRUNK, ControlAction.CLOSE_TRUNK -> R.drawable.ic_c_trunk
            ControlAction.FLASH_LIGHT -> R.drawable.ic_c_bolt
            ControlAction.HORN_WHISTLE -> R.drawable.ic_c_horn
            ControlAction.SEARCH_CAR -> R.drawable.ic_c_search
            else -> R.drawable.ic_c_power
        }

        fun tintOf(action: ControlAction): Int = when (action) {
            ControlAction.LOCK -> R.color.brand
            ControlAction.UNLOCK -> R.color.accent
            ControlAction.REMOTE_START -> R.color.info
            ControlAction.REMOTE_STOP -> R.color.danger
            ControlAction.OPEN_WINDOWS -> R.color.info
            ControlAction.CLOSE_WINDOWS -> R.color.txt_tertiary
            ControlAction.WINDOW_SLIT -> R.color.warn
            ControlAction.OPEN_SUNROOF -> R.color.info
            ControlAction.CLOSE_SUNROOF -> R.color.txt_tertiary
            ControlAction.OPEN_TRUNK -> R.color.warn
            ControlAction.CLOSE_TRUNK -> R.color.txt_tertiary
            ControlAction.FLASH_LIGHT -> R.color.warn
            ControlAction.HORN_WHISTLE -> R.color.purple
            ControlAction.SEARCH_CAR -> R.color.warn
            else -> R.color.txt_secondary
        }

        fun shortLabelOf(action: ControlAction): String = when (action) {
            ControlAction.LOCK -> "锁车"
            ControlAction.UNLOCK -> "解锁"
            ControlAction.REMOTE_START -> "远程启动"
            ControlAction.REMOTE_STOP -> "熄火"
            ControlAction.OPEN_WINDOWS -> "开窗"
            ControlAction.CLOSE_WINDOWS -> "关窗"
            ControlAction.WINDOW_SLIT -> "微开"
            ControlAction.OPEN_SUNROOF -> "开天窗"
            ControlAction.CLOSE_SUNROOF -> "关天窗"
            ControlAction.OPEN_TRUNK -> "开后备箱"
            ControlAction.CLOSE_TRUNK -> "关后备箱"
            ControlAction.FLASH_LIGHT -> "闪灯"
            ControlAction.HORN_WHISTLE -> "鸣笛"
            ControlAction.SEARCH_CAR -> "寻车"
            else -> action.label
        }
    }
}
