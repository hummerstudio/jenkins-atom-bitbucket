# jenkins-atom-bitbucket

Bitbucket build status — one atom for the two Bitbucket products, chosen by `flavor`:

| `flavor` | Product | Ported from | Was |
|---|---|---|---|
| `server` (default) | Bitbucket Server / Data Center | **stashNotifier** 2.7 + **bitbucket-push-and-pull-request** 4.1.0 | `bitbucket-status` 1.1.0 |
| `cloud` | Bitbucket Cloud | **bitbucket-build-status-notifier** 1.4.2 | `bitbucket-cloud-status` 1.0.0 |

They are two deployments with two APIs — that is why they were two atoms, and why they are still two code paths
(`ServerStatus`, `CloudStatus`) behind one `AtomTask`. The merge is a *dispatch*, not a unification: the point is that
a user who knows "Bitbucket" picks `flavor` and fills the four fields that flavour needs, instead of first having to
know whether their installation is Server or Cloud.

```
POST {serverUrl}/rest/build-status/1.0/commits/{sha}                      five fields, HTTP token or Basic
POST https://api.bitbucket.org/2.0/repositories/{ws}/{repo}/commit/{sha}/statuses/build   three fields, OAuth consumer
```

`flavor` is matched without regard to case; an empty value means `server`, so a job written for the old
`bitbucket-status` keeps its inputs and its behaviour with no new field. Any other value is refused with `40000`, and
the message names both values — the atom does not guess, because guessing would send a Server payload to Cloud and call
the 404 the product's answer.

## Inputs

Shared (`both flavours`):

| Field | Meaning |
|---|---|
| `flavor` | `server` / `cloud`; empty = `server` |
| `sha` | commit, defaults to `GIT_COMMIT` |
| `state` | defaults to `BUILD_STATUS`; Server maps it like the plugin, Cloud rejects a word outside its three |
| `key` | Server: defaults to `JOB_NAME`; Cloud: defaults to `md5(JOB_NAME#BUILD_NUMBER)` |
| `name` | display name; defaults to `JOB_NAME #BUILD_NUMBER` |
| `description` | Server: generated when empty; Cloud: left out of the payload when empty |
| `buildUrl` | defaults to `BUILD_URL`; required by Cloud |
| `repoSlug` | Server: repository of the pull request; Cloud: the repo, inferred from `GIT_URL` when empty |
| `verifyAfterWrite` | default `true` — read the status back instead of trusting the 2xx |

Server only:

| Field | Meaning |
|---|---|
| `serverUrl` | root, required, must be http(s) |
| `token` | HTTP access token (credential) → `Authorization: Bearer` |
| `username` + `password` | Basic auth; `password` is a credential (personal access token) |
| `considerUnstableAsSuccess` | `UNSTABLE` → `SUCCESSFUL` instead of `FAILED` |
| `notifyAborted` | `ABORTED` / `NOT_BUILT` are reported as `FAILED` instead of skipped |
| `includeBuildNumberInKey` | append `-BUILD_NUMBER` to the `JOB_NAME` key |
| `pullRequestUrl` or `projectKey` + `repoSlug` + `pullRequestId` | the pull request to act on |
| `prAction` | `none` / `approve` (green approves, red withdraws) / `decline` (red declines) |
| `comment` | PR comment, `${VAR}` expanded, posted and read back by id |

Cloud only:

| Field | Meaning |
|---|---|
| `workspace` | Bitbucket workspace / owner; inferred from `GIT_URL` when empty |
| `consumerKey` + `consumerSecret` | OAuth consumer → `client_credentials` at `tokenUrl` |
| `accessToken` | pre-issued token (credential); skips the exchange |
| `overrideLatestBuild` | `key=md5(JOB_NAME)`, `name=JOB_NAME`: one status per job |
| `apiUrl` / `tokenUrl` | defaults `https://api.bitbucket.org`, `https://bitbucket.org/site/oauth2/access_token` |

Outputs are the union: `status`, `state`, `key`, `apiUrl`, `verified` (both), `prAction` / `prVerified` / `commentId`
(server), `repo` / `tokenSource` (cloud).

## Error codes are per flavour

Nothing was renumbered, so a pipeline that matched on `errorCode` keeps matching:

| Code | server | cloud |
|---|---|---|
| `40000` | — | — (`flavor` is neither value; added by the merge) |
| `40001` | missing/invalid `serverUrl`, missing `sha` | missing workspace/repo/`sha`/url, bad `apiUrl` |
| `40002` | no credential, or a PR action without a pull request | no credential, or the token endpoint rejected the consumer |
| `40003` | — | state outside `SUCCESSFUL` / `FAILED` / `INPROGRESS` |
| `40004` | status POST unreachable / interrupted | token exchange unreachable, interrupted, or not JSON |
| `40005` | 401/403, or any other non-2xx from the status POST | status POST unreachable / interrupted |
| `40006` | read-back failed or disagrees | 401/403 on the status POST |
| `40007` | PR approve / withdraw / decline failed, or its read-back disagrees | any other non-2xx from the status POST |
| `40008` | the PR comment was refused | read-back failed or disagrees |
| `40009` | the comment was not stored / not readable | — |

## What the ports do beyond the plugins (unchanged from the two atoms)

- **Read back after the write.** Both plugins ignore the response. Both flavours default to `verifyAfterWrite=true`:
  a `204` in front of Bitbucket, or a `201` from a proxy that dropped the body, is not evidence.
- **Server:** the pull-request actions, and the `comment` input, which the plugin did not have.
- **Cloud:** unknown states are refused before the request; owner/repo come from inputs or `GIT_URL`, because an atom
  has no Jenkins SCM object.

## Build and test

```bash
mvn -B -ntp verify     # -> target/dist/bitbucket/, three IT classes
```

| Suite | Scenarios | What it judges |
|---|---|---|
| `BitbucketServerStatusIT` | 25 | `tools/fake_bitbucket.py` — payload recomputed from `input.json`, compared with what the server stored, the read-back and the PR state machine |
| `BitbucketCloudStatusIT` | 22 | `tools/fake_bitbucket_cloud.py` + `tools/check_cloud.py` — the same treatment for the Cloud API, OAuth exchange included |
| `BitbucketFlavorIT` | 4 | the dispatch: unknown flavour refused by name, empty flavour = Server, case-insensitive, `cloud` never speaks the Server API |

Nothing is skipped. `tools/check_server.py` is the Python second implementation of the Server contract, kept runnable
(`python3 tools/check_server.py --run <dir> …`) even though the Server suite now does that comparison in Java.
