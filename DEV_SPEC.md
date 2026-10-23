# 海鸥开发操作规范（强制）

> 本规范来自用户明确要求（原话：「所有问题一次做完一起推，也不要一个一个文件推！」）。
> 每次操作前必读；违反 = 浪费 CI 额度 + 用户暴怒。

## 铁律

0. **两级复盘（用户强制）** —— ① 每写完**一个功能**立刻复盘一次（编译风险/逻辑边界/
   残留行/括号配平/与现有代码的冲突）；② 所有功能写完、推之前，**从头整体复盘第二次**。
   两次都过才准推。用户会问「复盘了吗」——必须真的复盘过，不是嘴上说。
1. **一次 commit 推完所有文件** —— 用 Git Data API（blob → tree → commit → 更新 ref），
   **禁止** Contents API 逐个文件推（一次一个文件 = N 个 commit = N 次 CI）。
2. **构建只跑一个** —— 每次推送后立即检查 runs，发现排队/在跑的旧 commit 立刻 cancel，
   只留最新 HEAD 的那个。
3. **先复盘再推** —— 用户会问「复盘了吗」。推之前自己先过一遍：
   编译风险（@hide API 用反射/裸值）、逻辑边界（正则误匹配、null）、
   残留行（大块替换后检查尾巴）、括号配平。
4. **不确定能不能成的功能，先问再推** —— 别写了就推，让用户拍板。
5. **装机前先确认 APK 真下载成功** —— curl 后检查文件大小（>1MB），
   cp 到 /data/local/tmp 后确认存在，再 pm install。
   版本号用 `dumpsys package <pkg> | grep versionName` 核对。
6. **push 后必须回读自证，装机前必须验特征码**（事故 NB-PUSH-LOST，
   2026-10-23）—— push_many 推完立刻 `GET contents/<路径>?ref=<新commit>`
   拉回内容，验证长度/特征串与本地一致；装机后 `pm path` 拉设备 APK 解压
   dex grep 本轮功能特征串（infoMode/hookAmapAutoGuide/rightCol...）——
   **特征码不在 = 这次推送丢了，别报告"装好了"**。
   事故：NB2~NB6 五轮 push_many 的源码修改全部没进 main（本地 reposrc
   一直是新的，commit 里却是旧内容——上传通道丢内容，根因待查）。表现：
   设备装「新 UI 壳 + 旧逻辑」——NavCard 是批次 AN 旧版（无导航 GONE）
   所以「默认没有导航卡片」，XposedEntry 旧版所以 NB2/NB4 的导航 hook
   根本没装。**本地对不上 main 时，一律以「回读 commit 内容」为准，
   不信 push 的返回值**。

## Git Data API 一次多文件推送模板

```python
import json, base64, urllib.request, urllib.error, time

TOKEN = "<token>"
REPO = "canfunan/seagull-desktop"
BRANCH = "main"

def api(method, path, payload=None, tries=6):
    url = f"https://api.github.com/repos/{REPO}/{path}"
    last = None
    for i in range(tries):
        data = json.dumps(payload).encode() if payload is not None else None
        r = urllib.request.Request(url, data=data, method=method)
        r.add_header("Authorization", f"token {TOKEN}")
        r.add_header("Accept", "application/vnd.github+json")
        try:
            with urllib.request.urlopen(r, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code}: {e.read().decode()[:150]}"); raise
        except Exception as e:
            last = e; print(f"retry {i+1}: {e}"); time.sleep(5)
    raise last

def push_many(msg, files):
    """files: [(repo_path, local_path), ...] —— 一次 commit 全推完"""
    # 1. HEAD commit
    ref = api("GET", "git/ref/heads/" + BRANCH)
    head_sha = ref["object"]["sha"]
    # 2. HEAD 的 tree
    commit = api("GET", "git/commits/" + head_sha)
    base_tree = commit["tree"]["sha"]
    # 3. 每个文件建 blob + tree 项
    tree_items = []
    for repo_path, local in files:
        blob = api("POST", "git/blobs", {
            "content": base64.b64encode(open(local, "rb").read()).decode(),
            "encoding": "base64",
        })
        tree_items.append({
            "path": repo_path,
            "mode": "100644",
            "type": "blob",
            "sha": blob["sha"],
        })
    # 4. 新 tree（基于 base_tree，只改动的文件进 tree_items）
    tree = api("POST", "git/trees", {
        "base_tree": base_tree,
        "tree": tree_items,
    })
    # 5. 一次 commit
    new_commit = api("POST", "git/commits", {
        "message": msg,
        "tree": tree["sha"],
        "parents": [head_sha],
    })
    # 6. 更新 ref
    api("PATCH", "git/refs/heads/" + BRANCH, {"sha": new_commit["sha"]})
    print("一次推完:", len(files), "个文件 → commit", new_commit["sha"][:10])
    # 7. 回读自证（铁律 6，事故 NB-PUSH-LOST）：长度不符直接炸，别把没推上去当成功
    for repo_path, local in files:
        back = api("GET", "contents/" + repo_path + "?ref=" + new_commit["sha"])
        bc = base64.b64decode(back["content"])
        lc = open(local, "rb").read()
        if len(bc) != len(lc):
            raise AssertionError("回读长度不符 " + repo_path + ": main=" + str(len(bc))
                                 + " 本地=" + str(len(lc)) + "——重推！")
        print("  ✓", repo_path.split("/")[-1], len(bc), "B 与本地一致")
    return new_commit["sha"]

# 用法：
# push_many("feat: xxx", [
#     ("app/src/com/seagull/carlauncher/Foo.java", "appsrc/com/seagull/carlauncher/Foo.java"),
#     ("app/AndroidManifest.xml", "AndroidManifest.xml"),
# ])
```

## 构建监控 + 清理流程

```bash
# 推完后：只留最新 HEAD 的 run，其余全 cancel
for RID in $(curl -s -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/<repo>/actions/runs?per_page=5" | python3 -c "
import json,sys
for r in json.load(sys.stdin)['workflow_runs'][:5]:
    if r['status'] in ('queued','in_progress') and not r['head_sha'].startswith('<新commit前10位>'):
        print(r['id'])
"); do curl -s -X POST -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/<repo>/actions/runs/$RID/cancel" >/dev/null; done
```

## release tag 命名规律

- 格式：`v1.0.<commit 短 hash 前 7 位>`（不是 8 位！e1ba352 不是 e1ba3526）
- asset 名固定 `SeagullLauncher.apk`
- 下载：`https://github.com/<user>/<repo>/releases/download/<tag>/SeagullLauncher.apk`
- API 偶尔抽风（api.github.com 000 但 github.com 通）——等 30s 重试，别慌

## 两级复盘检查单（推前必过）

### 第一级：每个功能写完立刻复盘

- [ ] **编译风险**：@hide API 用反射或裸值（如 TYPE_NAVIGATION_BAR=2019、
      setLaunchTaskId 反射）；新增 import 都加了（ComponentName 等）
- [ ] **逻辑边界**：正则精确匹配（`d90003` 要防 `d190003` —— 用 `d'$D'( |$)'`）；
      null/空串检查；`grep -c` 返回值是字符串 "0" 不是 int
- [ ] **残留行**：大块 replace 后检查尾巴（`sed -n` 看改动前后 5 行；
      Python replace 静默失败用 assert 或改用 edit 工具）
- [ ] **括号配平**：`python3 -c "s=open(f).read(); print(s.count('{'),s.count('}'))"`
- [ ] **与现有代码冲突**：新方法名/字段名不撞车；调用点都存在（如 `refreshSelf()`
      这种不存在的方法——推前 grep 确认）
- [ ] **新增文件**：清单注册了没？exported 对不对（被别的 app 拉起要 true）？

### 第二级：全部写完，推之前，从头整体复盘

- [ ] 所有改动文件列出来，逐个过第一级清单
- [ ] **功能间相互影响**：新 hook 会不会影响旧 hook（包名分支顺序）？
      新服务会不会被现有逻辑误启？
- [ ] **用户场景走一遍**：从用户操作路径推演（连车机→自动推→底栏盖→掉线自愈），
      每步有代码支撑
- [ ] **装机验证预演**：装机命令、版本号核对、失败时看什么（见下）
- [ ] 历史踩坑过一遍（SKILL.md 的坑位表 + 本文件「vivo 车机投屏现状」）

## 装机核对清单

1. `curl -w "HTTP %{http_code} %{size_download}B"` —— 确认 200 且 >1MB
2. `su -c "cp ... /data/local/tmp/x.apk && ls -la /data/local/tmp/x.apk"` —— 确认拷贝成功
3. `pm install -r -t` 输出 `Success`
4. `dumpsys package <pkg> | grep versionName` —— 版本号是新的
5. **验特征码**（铁律 6）：`pm path` 拉设备 APK → 解压 dex → grep 本轮功能的
   特征串（如 `infoMode`/`quickBtn`/`hookAmapAutoGuide`/`rightCol`）——**不在
   就是推送丢了，别报告装好了，回铁律 6 重推**
6. 装机失败先看完整错误头（`INSTALL_PARSE_FAILED` = 清单问题，`SecurityException` = 权限/exported）

## vivo 车机投屏现状（2026-10-22，关键结论备查）

- 车机屏 = `vivo_car_screen`（d90003，1280x720，TRUSTED+SECURE）
- **vivo 车机启动器（com.vivo.carlauncher）已 `pm disable-user` 冻结** —— 框架踢不动海鸥桌面
- **默认 home 已 `pm set-home-activity` 设为海鸥** —— 框架 home 解析回退到海鸥
- 海鸥桌面上车机屏：`am start --display 90003 -f 0x18800000 -n com.seagull.carlauncher/.HomeActivity`
- **MediaListenerService 里有守护线程**：连车机自动推 + 掉了 5s 自愈
- 底栏 VivoCarToolBar 归 com.vivo.car.networking（系统 uid，hook 注不进）→ ToolBarCoverService overlay 盖
- vivo.car.networking 主进程杀不得（和投影同生共死）

## 用户红线（别再踩）

- **不要重启用户手机**（明确禁止）
- 不要一个一个文件推（本规范存在的原因）
- 不要反复小步构建（一次做完）
- 不要卡循环（思考里绕圈时立刻停，直接查现状/动手）
- 不要写用户没要的功能（问了再推，别自作主张推上去）
- 用户问「复盘了吗」= 真的在问，必须真的复盘
