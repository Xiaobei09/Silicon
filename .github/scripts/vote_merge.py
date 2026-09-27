#!/usr/bin/env python3
"""投票通过则 squash 合并。本脚本是合并闸门，独立复核票数，不信任 democracy job 的退出码。

为什么必须独立复核：action 的 switch 没有 default 分支，任何落不进
{opened,reopened,synchronize,closed} 的事件都会「什么都不做 → job 成功」，
产出并非真实评估的绿色 check。已实测遇到过（payloadAction='submitted'，
全日志 voting failure message / numVoters / Current Voting Result 计数均为 0）。
合并不可逆，绝不能只凭「上一个 job 是绿的」就执行。

计票语义（action 侧，Xiaobei09/git-democracy）：
  reactions.ts  遍历 pulls.listReviews，每人最后一次 review 覆盖前一次
                (result.set(login, vote))；PR 作者被自动记为 +1
  reactions.ts  weight = voters.get(user) ?? 0；weight>0 且 vote!=0 才计入 numVoters
  voting.ts     percentage = for/(for+against)*100

【本脚本与 action 的三处有意分歧，都是往更严的方向】
1. 0 票时 action 得 0/0=NaN，NaN<100 为 false 会静默放行；这里显式判失败。
2. action 的 weightedVoteTotaling 循环里没有任何时间过滤 —— PR 更新后
   历史票依然全部计入。这里按需求改为：**PR 最近一次更新之前的票一律作废，
   但作者自投的那票保留**（作者无法 review 自己的 PR，该票是 action 合成的，
   不受时间限制）。投票窗口也改为从「PR 最近一次更新」起算，
   而不是 action 用的 head.repo.pushed_at（那是头仓任意分支的 push 时间，
   别人推别的分支也会把它顶掉，并不等于本 PR 的更新时间）。
3. action 判定通过后由人点合并；这里用 REST merge 并**把 sha 钉死到本次复核过的
   头提交**，堵住「复核完到合并前被推了新提交」的 TOCTOU 窗口（`gh pr merge`
   合的是当时的当前 head，不是我们复核过的那个）。

【退出码约定】——两类结局必须可区分，否则「合并闸门跑过了」会被误读成「合并了」：
  0 = 脚本正常跑完并做出明确判定（已合并 / 判定不合并：草稿、非 open、票未达标）
  1 = 无法判定或动作失败（环境变量缺失、取不到更新时间、合并请求被拒）
"拿不到数据" 一律 fail-closed（退出 1、不合并），绝不静默放行。
每条路径都打印一行以「合并结果: 」开头的指纹，便于在 run 日志里 grep 确认
到底走了哪条（不能只看 job 的 conclusion）。
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

APPROVED, CHANGES_REQUESTED = "APPROVED", "CHANGES_REQUESTED"
RESULT_PREFIX = "合并结果: "


def api(path, token, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def parse_ts(s):
    """解析 GitHub 的时间戳。

    无法解析时**返回 None 而不是抛异常** —— 调用方据此 fail-closed；
    若这里抛 ValueError，下面 `if update_time is None` 那道守卫就永远走不到，
    等于把「安全兜底」变成了「崩溃路径」。
    """
    if not s:
        return None
    s = str(s).strip()
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S.%fZ"):
        try:
            dt = datetime.strptime(s, fmt)
        except ValueError:
            continue
        return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)
    return None


def load_yaml_map(path, default=None):
    """只支持 name: number 这种平坦映射，够 .voters.yml / .voting.yml 用。"""
    out = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].rstrip()
                if not line or line in ("---", "..."):
                    continue
                k, _, v = line.partition(":")
                k, v = k.strip().strip("\"'"), v.strip().strip("\"'")
                if not k:
                    continue
                try:
                    out[k] = int(v)
                except ValueError:
                    out[k] = v
    except FileNotFoundError:
        if default is None:
            raise
    return out


def latest_reviews(reviews):
    """每人最后一次 review 胜出，复刻 action 的 result.set(login, vote) 覆盖语义。"""
    latest = {}
    for r in reviews:
        login = (r.get("user") or {}).get("login")
        if login is None:                      # action: not counting vote of null user
            continue
        state = r.get("state")
        vote = 1 if state == APPROVED else (-1 if state == CHANGES_REQUESTED else 0)
        sub = r.get("submitted_at") or ""
        if login in latest and latest[login][1] >= sub:
            continue                          # 只保留时间上更晚的那次
        latest[login] = (vote, sub)
    return latest


def tally(reviews, voters, author, update_time=None, now=None):
    """返回 (numVoters, forIt, againstIt, per_user, stale_dropped)。

    update_time 之后提交的票才有效；作者的合成票恒有效。
    """
    latest = latest_reviews(reviews)
    counted, dropped = {}, []
    for login, (vote, sub) in latest.items():
        t = parse_ts(sub)
        if update_time is not None and t is not None and t <= update_time:
            dropped.append(login)             # PR 更新前投的票 → 作废
            continue
        counted[login] = vote
    if author:                                 # 作者无法 review 自己，action 替他投 +1；恒有效
        counted[author] = 1
        if author in dropped:
            dropped.remove(author)

    num_voters = for_it = against_it = 0
    per_user = {}
    for login, vote in counted.items():
        w = voters.get(login)
        w = w if isinstance(w, int) else 0
        if w > 0 and vote != 0:
            num_voters += 1
            per_user[login] = (vote, w)
        if vote > 0:
            for_it += vote * w
        elif vote < 0:
            against_it += -vote * w
    return num_voters, for_it, against_it, per_user, dropped


def merge_pr(repo, pr, sha, token):
    """用 REST merge 把 sha 钉死到已复核的头提交。

    返回 (merged: bool, detail: str)。传了 sha 之后，若在「复核完成」到
    「合并生效」之间有人推了新提交，GitHub 会返回 409 而**不会**把没复核过
    的新提交合进去 —— 这正是 gh pr merge 留给我们的 TOCTOU 窗口。
    """
    path = "/repos/%s/pulls/%s/merge" % (repo, pr)
    try:
        out = api(path, token, method="PUT",
                  body={"sha": sha, "merge_method": "squash"})
        return bool(out.get("merged")), (out.get("message") or "")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("message", detail)
        except ValueError:
            pass
        return False, "HTTP %s %s" % (e.code, detail.strip())


def delete_head_branch(head, base_repo, token):
    """合并成功后尽力删掉贡献者分支。删不掉不影响合并结果，只提示。

    这里必须吞掉【所有】异常：合并此刻已经生效，若清理阶段的网络抖动让异常
    逃出去，job 会以退出码 1 收场 —— 维护者会看到「合并失败」的红叉，
    而 PR 其实已经合上了。这类「事后失败」比直接报错更有误导性。
    """
    try:
        if not head:
            return "无 head 信息，跳过"
        repo = ((head.get("repo") or {}).get("full_name")) or ""
        ref = head.get("ref") or ""
        if not repo or not ref:
            return "缺少 repo/ref 信息，跳过"
        if repo == base_repo:
            return "%s 属本仓，交给仓库的自动删分支设置" % ref
        api("/repos/%s/git/refs/heads/%s" % (repo, urllib.parse.quote(ref)),
            token, method="DELETE")
        return "已删除 %s:%s" % (repo, ref)
    except urllib.error.HTTPError as e:
        return "删除 %s:%s 失败（HTTP %s，不影响合并）" % (repo, ref, e.code)
    except Exception as e:                       # 含 URLError / ValueError / KeyError
        return "清理异常（不影响合并）: %s: %s" % (type(e).__name__, e)


def report(msg):
    print(RESULT_PREFIX + msg)


def main(now=None):
    now = now or datetime.now(timezone.utc)
    token, repo, pr = (os.environ.get(k) for k in ("GH_TOKEN", "REPO", "PR"))
    if not (token and repo and pr):
        sys.exit("缺少 GH_TOKEN / REPO / PR 环境变量（无法判定，fail-closed）")

    cfg = load_yaml_map(".voting.yml")
    voters = load_yaml_map(".voters.yml", default={})
    need_pct = int(cfg.get("percentageToApprove", 100))
    need_voters = int(cfg.get("minVotersRequired", 1))
    window_min = int(cfg.get("minVotingWindowMinutes", 0))

    data = api("/repos/%s/pulls/%s" % (repo, pr), token)
    head = data.get("head") or {}
    if data.get("state") != "open":
        report("未合并（PR 状态为 %s）" % data.get("state"))
        return
    if data.get("draft"):
        report("未合并（PR 是草稿）")
        return
    author = (data.get("user") or {}).get("login")

    # 「PR 最近一次更新」= 头提交的时间。取不到就让脚本失败（合并闸门应 fail-closed）。
    sha = head.get("sha")
    if not sha:
        sys.exit("取不到 head.sha，无法确定 PR 最近一次更新，按 fail-closed 处理，不合并")
    head_commit = api("/repos/%s/commits/%s" % (repo, sha), token)
    update_time = parse_ts((head_commit.get("commit") or {}).get("committer", {}).get("date"))
    if update_time is None:
        sys.exit("无法确定 PR 最近一次更新时间，按 fail-closed 处理，不合并")

    reviews, page = [], 1
    while True:
        batch = api("/repos/%s/pulls/%s/reviews?per_page=100&page=%d" % (repo, pr, page), token)
        if not batch:
            break
        reviews.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    num_voters, for_it, against_it, per_user, dropped = tally(
        reviews, voters, author, update_time, now)
    total = for_it + against_it
    pct = (for_it / total * 100) if total else 0.0

    print("### 自动合并复核结果")
    print("")
    print("- PR #%s（作者 `%s`，状态 %s）" % (pr, author, data.get("state")))
    print("- 复核的头提交: `%s`" % sha)
    print("- 本脚本的窗口起点（头提交时间）: %s" % update_time.isoformat())
    # action 的窗口起点是 head.repo.pushed_at —— 头仓【任意分支】的最后 push，
    # 与本 PR 无关。实测 PR #67 两者相差 100 分钟（那次 push 是别人往同仓别的
    # 分支推的）。窗口起点错 → 投票窗口被无谓拉长；全仓又没有任何 schedule，
    # 被拉长之后没有任何事件会重新评估 → PR 静默卡住。把差值打出来便于定位。
    action_start = parse_ts((head.get("repo") or {}).get("pushed_at"))
    if action_start:
        print("- action 的窗口起点（head.repo.pushed_at）: %s" % action_start.isoformat())
        if action_start != update_time:
            print("  ⚠️ 两者相差 %+.0f 分钟 —— action 把「头仓任意分支的最后 push」"
                  "当成了本 PR 的更新时间。窗口起点被拉长 %.0f 分钟。"
                  % (((action_start - update_time).total_seconds() / 60),
                     max(0.0, (action_start - update_time).total_seconds() / 60)))
    print("- 登记投票人: %s" % (", ".join(sorted(voters)) or "无"))
    print("- 计票: numVoters=%d 赞成=%d 反对=%d 赞成率=%.1f%%"
          % (num_voters, for_it, against_it, pct))
    print("- 门槛: 赞成率>=%d%% 最少投票人>=%d 窗口>=%d 分钟"
          % (need_pct, need_voters, window_min))
    if dropped:
        print("- 因早于最近一次更新而作废的票: %s" % ", ".join(sorted(dropped)))
    print("")
    print("| 投票人 | 立场 | 权重 |")
    print("| --- | --- | --- |")
    for login in sorted(per_user):
        vote, w = per_user[login]
        print("| `%s` | %s | %d |" % (login, "赞成" if vote > 0 else "反对", w))

    reasons = []
    if num_voters < need_voters:
        reasons.append("投票人 %d < 门槛 %d" % (num_voters, need_voters))
    if total == 0:
        reasons.append("无任何有效票（action 在此情形会因 0/0=NaN 而静默放行）")
    elif pct < need_pct:
        reasons.append("赞成率 %.1f%% < 门槛 %d%%" % (pct, need_pct))
    if window_min > 0:
        end = update_time + timedelta(minutes=window_min)
        if end > now:
            reasons.append("投票窗口未满（%s 之后才能判定）" % end.isoformat())
    if reasons:
        print("")
        print("不合并：" + "；".join(reasons))
        report("未合并（%s）" % "；".join(reasons))
        return

    print("")
    print("票数复核通过，执行 squash 合并（头提交已钉死为 `%s`）" % sha)
    merged, detail = merge_pr(repo, pr, sha, token)
    if not merged:
        # 走到这里说明本脚本自己的门槛全过了，票数判定没问题。合并被拒多半来自
        # ruleset 的 required check —— 尤其 democracy：它的窗口起点用的是
        # head.repo.pushed_at（见上），可能刚被头仓的无关 push 顶到未来。
        # GitHub 对这种拒绝只回一句 "not mergeable"，不提示真正原因，故在此点明。
        report("合并被拒：%s" % (detail or "未知原因"))
        print("- 本脚本的票数判定已全部通过，票数不是被拒原因。")
        print("- 优先排查 ruleset 的 required check（尤其 `democracy`）：")
        print("  ① 它是否在本 PR 当前头提交上重跑过（同名 context 只要有一条 failure 就整体判红）；")
        print("  ② 它的投票窗口起点用的是 head.repo.pushed_at，可能被头仓【任意分支】的 push 顶到未来。")
        print("  修复办法：在本 PR 上产生一次真实事件（再提交一次 review，或 push 一次）让它重跑。")
        sys.exit(1)
    report("已合并 %s（头提交 `%s`）" % (detail or "", sha))
    print("- 贡献者分支清理: %s" % delete_head_branch(head, repo, token))


if __name__ == "__main__":
    main()
