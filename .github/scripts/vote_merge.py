#!/usr/bin/env python3
"""投票通过则 squash 合并。独立复核票数，不信任 democracy job 的退出码。

为什么必须独立复核：action 的 switch 没有 default 分支，任何落不进
{opened,reopened,synchronize,closed} 的事件都会「什么都不做 → job 成功」，
产出一条并非真实评估的绿色 check。已实测遇到过（payloadAction='submitted'，
全日志 voting failure message / numVoters / Current Voting Result 计数均为 0）。
合并是不可逆操作，绝不能只凭「上一个 job 是绿的」就执行。

计票语义严格复刻 Xiaobei09/git-democracy：
  reactions.ts: 遍历 pulls.listReviews，每人最后一次 review 覆盖前一次
                (result.set(login, vote))；PR 作者被自动记为 +1
  reactions.ts: weight = voters.get(user) ?? 0；weight>0 且 vote!=0 才计入 numVoters
  voting.ts:     percentage = for/(for+against)*100
唯一一处比 action 更严：0 票时 action 得 NaN，NaN<100 为 false 会放行；
这里显式判失败。合并闸门宁可漏合不可错合。
"""
import json
import os
import sys

import urllib.request

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
                k = k.strip().strip("\"'")
                v = v.strip().strip("\"'")
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


def tally(reviews, voters, author):
    """复刻 weightedVoteTotaling。返回 (numVoters, forIt, againstIt, per_user)。"""
    latest = {}
    for r in reviews:
        login = (r.get("user") or {}).get("login")
        if login is None:                      # action: not counting vote of null user
            continue
        state = r.get("state")
        latest[login] = 1 if state == APPROVED else (-1 if state == CHANGES_REQUESTED else 0)
    if author:                                  # action: 作者无法 review 自己的 PR，替他投 +1
        latest[author] = 1

    num_voters = for_it = against_it = 0
    per_user = {}
    for login, vote in latest.items():
        weight = voters.get(login)
        weight = weight if isinstance(weight, int) else 0
        if weight > 0 and vote != 0:
            num_voters += 1
        if vote > 0:
            for_it += vote * weight
        elif vote < 0:
            against_it += -vote * weight
        if weight > 0 and vote != 0:
            per_user[login] = (vote, weight)
    return num_voters, for_it, against_it, per_user


def main():
    token = os.environ.get("GH_TOKEN", "")
    repo = os.environ.get("REPO", "")
    pr = os.environ.get("PR", "")
    if not (token and repo and pr):
        sys.exit("缺少 GH_TOKEN / REPO / PR 环境变量")

    cfg = load_yaml_map(".voting.yml")
    voters = load_yaml_map(".voters.yml", default={})
    need_pct = int(cfg.get("percentageToApprove", 100))
    need_voters = int(cfg.get("minVotersRequired", 1))

    data = api("/repos/%s/pulls/%s" % (repo, pr), token)
    if data.get("state") != "open":
        sys.exit("PR #%s 状态为 %s，不合并" % (pr, data.get("state")))
    if data.get("draft"):
        sys.exit("PR #%s 是草稿，不合并" % pr)
    author = (data.get("user") or {}).get("login")

    reviews = []
    page = 1
    while True:
        batch = api("/repos/%s/pulls/%s/reviews?per_page=100&page=%d" % (repo, pr, page), token)
        if not batch:
            break
        reviews.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    num_voters, for_it, against_it, per_user = tally(reviews, voters, author)
    total = for_it + against_it
    pct = (for_it / total * 100) if total else 0.0   # 0 票时按 0% 计（比 action 的 NaN 严）

    lines = [
        "### 自动合并复核结果",
        "",
        "- PR: #%s（作者 `%s`）" % (pr, author),
        "- 登记投票人: %s" % (", ".join(sorted(voters)) or "无"),
        "- 计票: numVoters=%d  赞成权重=%d  反对权重=%d  赞成率=%.1f%%"
        % (num_voters, for_it, against_it, pct),
        "- 门槛: 赞成率>=%d%%  最少投票人>=%d" % (need_pct, need_voters),
        "",
        "| 投票人 | 立场 | 权重 |",
        "| --- | --- | --- |",
    ]
    for login in sorted(per_user):
        vote, weight = per_user[login]
        lines.append("| `%s` | %s | %d |" % (login, "赞成" if vote > 0 else "反对", weight))
    print("\n".join(lines))

    reasons = []
    if num_voters < need_voters:
        reasons.append("投票人 %d < 门槛 %d" % (num_voters, need_voters))
    if pct < need_pct:
        reasons.append("赞成率 %.1f%% < 门槛 %d%%" % (pct, need_pct))
    if total == 0:
        reasons.append("无任何有效票（action 在此情形会因 0/0=NaN 而静默放行）")
    if reasons:
        print("不合并：" + "；".join(reasons))
        sys.exit(0)

    print("票数复核通过，执行 squash 合并")
    import subprocess
    subprocess.run(
        ["gh", "pr", "merge", pr, "--repo", repo, "--squash", "--delete-branch"],
        check=True,
    )


if __name__ == "__main__":
    main()
