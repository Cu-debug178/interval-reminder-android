# REA 安卓静态分析学习记录

## 样本与范围

2026-11-05，分析本机 Waydroid 已安装的“不背单词”APK，不读取应用私有账户或学习数据。样本包名为 `cn.com.langeasy.LangEasyLexis`，版本 `5.11.7`（versionCode 374），APK 的 SHA-256 为 `f3fa7491e51659a095362b1e74f88cead5fd8bab2f4538f7c625e03997ec46b8`。这是当前设备上的一个版本，不代表其他版本。

使用 REA npm 缓存中的 `rea-agents` 4.1.0 和 REA 指定的 `jadx-headless-mcp` 0.7.1；后者 SHA-256 与 REA 本地文档固定值一致。分析对象是本机 APK，只研究和记录观察，不复制其中专有代码或音频。

## 方法与观察

设置变量（APK 路径可以通过设备包管理器查询，再指向本机对应文件），然后使用 REA 的安卓类搜索：

```bash
export REA_JADX_MCP_JAR="$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar"
REA="$HOME/.npm/_npx/f39168ee6deb1e97/node_modules/rea-agents/scripts/rea.mjs"
node "$REA" search-android-classes "$APK" cn.com.langeasy --format json --filter-output normalized_result
```

本次按 `cn.com.langeasy` 搜索可见类名时返回 `R`、`activity.MainActivity`、`activity.NewLearnActivity`；按 `Syllable`、`Phon`、`Voice` 搜索未见匹配。搜索类名不是搜索全部方法、运行时加载内容或服务器端逻辑。尝试用 `inspect-android-class` 查看 `s.h.e.l.l.A` 和 `NewLearnActivity`，两次均在 90 秒外部时限内未返回结果，不能据此判定方法体为空或实现不存在。

另外，以 ZIP 目录只读查看发现了 `res/drawable/icon_phonetic_uk.xml`、`res/drawable/icon_phonetic_us.xml`、`res/layout/phonetic_layout.xml`，以及 `assets/ijm_lib/` 下多个架构的原生库和三个 DEX。资源名称只表明 APK 包含相关资源，不证明资源实际如何使用、发音是录音还是语音合成、或音节是怎样产生的。`s.h.e.l.l.A` 与原生库是需要进一步调查的保护或加载线索，不足以单独确定具体加固方案。

## 能学到什么

静态分析可从包信息、资源、类搜索逐步收窄范围；每一步都应保留样本版本、工具版本和阴性结果的覆盖范围。下一步可以在用户自行操作的界面上观察单词及其展示方式，再对照可见资源；要证明声音来源或分段算法，还需要能可靠关联到相关实现或运行时行为的证据。当前未定位算法，也没有证实存在独特方法。

英语文字、音素和音节之间不总是一一对应；拿词典音标直接按字母长度切分并不可靠。若要在自己的应用中实现，应先明确展示的是音标分段、文字音节，还是可逐段播放的声音，再选择有授权、带相应对齐信息的数据源，无法确认时允许用户修正。
