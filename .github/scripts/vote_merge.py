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

【本脚本与 action 的两处有意分歧，都是往更严的方向】
1. 0 票时 action 得 0/0=NaN，NaN<100 为 false 会静默放行；这里显式判失败。
2. action 的 weightedVoteTotaling 循环里没有任何时间过滤 —— PR 更新后
   历史票依然全部计入。这里按需求改为：**PR 最近一次更新之前的票一律作废，
   但作者自投的那票保留**（作者无法 review 自己的 PR，该票是 action 合成的，
   不受时间限制）。投票窗口也改为从「PR 最近一次更新」起算，
   而不是 action 用的 head.repo.pushed_at（那是头仓任意分支的 push 时间，
   别人推别的分支也会把它顶掉，并不等于本 PR 的更新时间）。
"""
import json
import os
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

APPROVED, CHANGES_REQUESTED = "APPROVED", "CHANGES_REQUESTED"


def api(path, token):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def parse_ts(s):
    if not s:
        return None
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


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


def main():
    token, repo, pr = (os.environ.get(k) for k in ("GH_TOKEN", "REPO", "PR"))
    if not (token and repo and pr):
        sys.exit("缺少 GH_TOKEN / REPO / PR 环境变量")

    cfg = load_yaml_map(".voting.yml")
    voters = load_yaml_map(".voters.yml", default={})
    need_pct = int(cfg.get("percentageToApprove", 100))
    need_voters = int(cfg.get("minVotersRequired", 1))
    window_min = int(cfg.get("minVotingWindowMinutes", 0))

    data = api("/repos/%s/pulls/%s" % (repo, pr), token)
    if data.get("state") != "open":
        sys.exit("PR #%s 状态为 %s，不合并" % (pr, data.get("state")))
    if data.get("draft"):
        sys.exit("PR #%s 是草稿，不合并" % pr)
    author = (data.get("user") or {}).get("login")

    # 「PR 最近一次更新」= 头提交的时间。取不到就让脚本失败（合并闸门应 fail-closed）。
    head = api("/repos/%s/commits/%s" % (repo, data["head"]["sha"]), token)
    update_time = parse_ts(head["commit"]["committer"]["date"])
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
        reviews, voters, author, update_time)
    total = for_it + against_it
    pct = (for_it / total * 100) if total else 0.0

    print("### 自动合并复核结果")
    print("")
    print("- PR #%s（作者 `%s`，状态 %s）" % (pr, author, data.get("state")))
    print("- PR 最近一次更新（头提交时间）: %s" % update_time.isoformat())
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
    if pct < need_pct:
        reasons.append("赞成率 %.1f%% < 门槛 %d%%" % (pct, need_pct))
    if total == 0:
        reasons.append("无任何有效票（action 在此情形会因 0/0=NaN 而静默放行）")
    if window_min > 0:
        end = update_time + timedelta(minutes=window_min)
        if end > (now or datetime.now(timezone.utc)):
            reasons.append("投票窗口未满（%s 之后才能判定）" % end.isoformat())
    if reasons:
        print("")
        print("不合并：" + "；".join(reasons))
        sys.exit(0)

    print("")
    print("票数复核通过，执行 squash 合并")
    subprocess.run(["gh", "pr", "merge", pr, "--repo", repo,
                    "--squash", "--delete-branch"], check=True)


if __name__ == "__main__":
    main()
