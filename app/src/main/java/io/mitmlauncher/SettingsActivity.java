package io.mitmlauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。
 *
 * 界面结构：
 *   ┌───────────────────────────┐
 *   │ ←  设置                   │  ← ActionBar（返回箭头由系统按 parentActivityName 自动画）
 *   ├───────────────────────────┤
 *   │ 授予文件访问权限           │
 *   │ 未授予                    │  ← 副标题显示当前状态
 *   │ 工作目录                   │
 *   │ /storage/.../Download/... │  ← 副标题显示路径，点击复制
 *   └───────────────────────────┘
 *
 * ★ 为什么用 ListView + BaseAdapter，而不是在 XML 里手写几行 LinearLayout：
 *   这个页面**必然会扩**（以后会加「导出完整日志」「导出 CA 证书」「mitmweb 开关」
 *   等）。两者加一项的成本差异：
 *     ListView     —— 数据列表里加一个元素 + onClick 里加一个 case（一处改动）
 *     手写布局      —— XML 加一段 + Java 加 findViewById + 加监听（两处改动，删项易漏）
 *   另外点击高亮、分隔线都由 ListView 负责，不用自己写 selector。
 *   当前只有 2 项，用 ListView 看起来"重"了一点，但换来的是后续每次扩展都更省事。
 *
 * ★ 没有用支持库的 RecyclerView：那要额外引入 androidx.recyclerview 依赖，
 *   而本页最多 8 项左右，ListView 完全够用，不值得为它加依赖。
 *
 * ★ ActionBar 为什么能用：
 *   官方文档明确写了「所有使用默认主题背景的 activity 均使用 ActionBar 作为应用栏」，
 *   本工程没有设置 android:theme，用的是默认主题，所以 ActionBar 存在。
 *   把它用起来了就不必自己画标题栏，返回箭头也是系统按清单里的
 *   android:parentActivityName 自动生成的。
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "MitmLauncherUI";

    /**
     * 设置项。用一个小模型 + 列表，是「加一项只改一处」的前提。
     *
     * summary 是**动态**的（如权限状态、当前工作目录），所以每次进界面都会重建列表。
     */
    private static final class Row {
        final String title;
        final String summary;

        Row(String title, String summary) {
            this.title = title;
            this.summary = summary;
        }
    }

    private ListView list;
    private final List<Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings);
        setContentView(R.layout.settings_list);

        list = findViewById(R.id.settingsList);
        list.setAdapter(new RowAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(position));

        // ★ 显示返回箭头。前提是清单里给这个 activity 声明了 android:parentActivityName，
        //   否则 ActionBar 找不到"上一级"就不会画箭头。
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ 每次回到前台都重建列表：权限状态、工作目录都可能在别处被改动
        //   （用户去系统设置授权后返回），必须重新取一次真实状态。
        //   这是"现查"而不是"缓存 + 失效通知"——现查的成本是一次系统调用，可忽略。
        rebuildRows();
    }

    /** 依据当前真实状态重建列表内容。 */
    private void rebuildRows() {
        rows.clear();
        rows.add(new Row(getString(R.string.set_perm_title),
                getString(Files.hasStorageAccess(this)
                        ? R.string.set_perm_summary_granted
                        : R.string.set_perm_summary_missing)));
        rows.add(new Row(getString(R.string.set_workdir_title), Files.workDirPath()));
        ((RowAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    /** 点击某一行的分发。加设置项就在这里加一个 case。 */
    private void onRowClicked(int position) {
        switch (position) {
            case 0:
                requestStorageAccess();
                break;
            case 1:
                copyWorkDir();
                break;
            default:
                break;
        }
    }

    /**
     * 跳系统页让用户开启「所有文件访问权」。
     *
     * ★ 为什么不用 requestPermissions 走运行时权限：Android 11+ 的
     *   MANAGE_EXTERNAL_STORAGE 是一个「特殊权限」，**不能**用 requestPermissions 申请，
     *   必须把用户引到系统设置页自己开关。
     *   而 Android 10 及以下才走运行时权限（此处简化为同样跳应用详情页，
     *   在那一页里开关存储权限）。
     *
     * ★ 已经授权时不跳转，只给一句提示 —— 避免用户以为点了没反应。
     *
     * ★ 跳转要包 try/catch：极少部分 ROM 没有这个设置页，
     *   一旦 ActivityNotFoundException 而没接，就是直接崩。
     */
    private void requestStorageAccess() {
        if (Files.hasStorageAccess(this)) {
            Toast.makeText(this, R.string.set_perm_summary_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(Files.buildStorageAccessIntent(this));
        } catch (Throwable t) {
            Log.w(TAG, "open storage settings failed", t);
            Toast.makeText(this, R.string.set_perm_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 点击工作目录那一行时复制路径。 */
    private void copyWorkDir() {
        String path = Files.workDirPath();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("workdir", path));
        Toast.makeText(this, R.string.set_workdir_copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // ★ ActionBar 的返回箭头会以 android.R.id.home 的形式送到这里。
        //   不处理的话点箭头没反应。
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 极简 adapter：把一个 Row 铺到 settings_item.xml 上。
     *
     * ★ 用 ViewHolder 缓存两个 TextView：ListView 会复用 convertView，
     *   每次都 findViewById 是浪费（虽然本页只有 2 项，但这是标准写法，
     *   以后列表变长也不会退化）。
     */
    private final class RowAdapter extends BaseAdapter {

        private final class Holder {
            TextView title;
            TextView summary;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.settings_item, parent, false);
                h = new Holder();
                h.title = convertView.findViewById(R.id.rowTitle);
                h.summary = convertView.findViewById(R.id.rowSummary);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }

            Row row = rows.get(position);
            h.title.setText(row.title);
            h.summary.setText(row.summary);
            // 副标题为空时直接隐藏，免得占一块空高度
            h.summary.setVisibility(row.summary.isEmpty() ? View.GONE : View.VISIBLE);
            return convertView;
        }
    }
}
