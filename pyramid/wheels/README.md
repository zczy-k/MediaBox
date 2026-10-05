# jsonpath 0.54 本地 wheel

`jsonpath` 0.54(Phil Budne,MIT)只发布过 Python 2 源码包,PyPI 上没有 wheel,
且源码使用 py2 语法(`print` 语句、`xrange`、`file()`),任何 Python 3 环境都无法安装。
Chaquopy 官方仓库只托管含原生扩展的包,且 17.0 起 pip 固定带 `--only-binary :all:`,不再从索引安装 sdist,因此本地移植。

- `jsonpath-0.54-py3-none-any.whl`:py3 移植后的成品 wheel,由 `pyramid/build.gradle.kts` 的
  `chaquopy.defaultConfig.pip.install("wheels/jsonpath-0.54-py3-none-any.whl")` 安装。
- `src/`:移植源码与 setup.py,便于复现。

移植内容(仅机械替换,逻辑未动):

1. `print "..."` 语句改为 `print()` 函数(全部位于 `if debug` 分支);
2. `xrange` → `range`;
3. `except Exception, e:` → `except Exception as e:`;
4. CLI 入口 `file()` → `open()`。

已验证:`import jsonpath`、通配符、`?(filter)`、切片语法与原版行为一致。
