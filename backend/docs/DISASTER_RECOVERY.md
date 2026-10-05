# Unis — Disaster Recovery Plan and Incident Runbook

Owner: Charles Lamb Jr.
Last reviewed: 2026-10-05
Next review due: _________ (review every 3 months, and after any infrastructure change)

---

## 1. What this covers

Unis runs on four services. If any one of them fails, part or all of the product
stops working.

| Layer | Service | What breaks if it fails |
|---|---|---|
| Web app | Netlify | Nobody can load the site |
| API | Railway | Site loads but nothing works — no login, no play, no vote |
| Database | Supabase (Postgres) | Same as Railway failing; all data is here |
| Media | Cloudflare R2 | Site works but audio and images don't load |

Supporting services: GitHub (code and automated backups), Stripe (payments),
Resend (email), Twilio (SMS).

---

## 2. Recovery targets

| Measure | Target | Why |
|---|---|---|
| RPO — most data that can be lost | 24 hours | Backups run once daily at 08:30 UTC |
| RTO — database rebuilt from backup | _____ minutes (fill in from last drill) | Measured, not estimated |
| Time to know something is wrong | Under 10 minutes | 5-minute monitor checks plus alert delivery |

**RPO in plain terms:** if the database were destroyed at 08:00 UTC, everything
since the previous morning's backup would be gone. That is acceptable before
launch. After launch, Supabase Pro's own daily backups narrow this, and
point-in-time recovery narrows it further.

---

## 3. How I find out

### Automatic alerts

| Alert source | Watches | Reaches me by |
|---|---|---|
| UptimeRobot — "Unis website" | The Netlify site | Email + phone push |
| UptimeRobot — "Unis backend health" | `/actuator/health` on Railway, which also fails if Supabase is unreachable | Email + phone push |
| UptimeRobot — "Unis media (R2)" | A known media file in R2 | Email + phone push |
| Healthchecks.io — "Unis daily backup" | That the daily backup ran | Email |
| GitHub Actions | A backup job that ran but failed | Email |

**No news is good news.** Nothing is sent when everything is healthy. A silent
morning means the site is up and last night's backup completed.

### Checking on purpose

- UptimeRobot dashboard — current status of all three monitors
- Healthchecks.io — when the last backup ran
- GitHub → Actions tab → Daily database backup — the last 30 runs
- Cloudflare → R2 → `unis-db-backups` → Objects — the actual backup files

---

## 4. First response: find the failing layer

When an alert arrives, do this before anything else. It takes about two minutes
and prevents fixing the wrong thing.

1. Open the site in a browser. Does it load at all?
2. Open the backend health URL. Does it return `UP`?
3. Play a song. Does audio load?

Read the result:

| Site | Health URL | Audio | Conclusion | Go to |
|---|---|---|---|---|
| Down | — | — | Netlify or DNS | §5.1 |
| Up | Down / won't load | — | Railway or Supabase | §5.2 |
| Up | `DOWN` with database error | — | Supabase | §5.3 |
| Up | `UP` | Fails | Cloudflare R2 | §5.4 |
| Up | `UP` | Works | Not an outage — probably a bug or a third party | §5.5 |

Before concluding it's my fault, check the provider status pages. A provider
outage means waiting and communicating, not debugging:

- status.netlify.com
- status.railway.com
- status.supabase.com
- cloudflarestatus.com

---

## 5. Actions by failing layer

### 5.1 Netlify — site won't load

1. Netlify dashboard → the Unis site → **Deploys**. Is the latest deploy failed
   or still building?
2. If the latest deploy broke the site: open the last known-good deploy and use
   **Publish deploy** to put it back live. This is instant and does not need a
   rebuild.
3. If deploys look fine, check DNS: the custom domain settings page will flag a
   domain or certificate problem.
4. If Netlify itself is down, there is nothing to fix. Post an update and wait.

### 5.2 Railway — backend unreachable

1. Railway dashboard → backend service → **Deployments**. Check whether the
   latest deployment crashed or is restarting repeatedly.
2. Open **Logs** and read the most recent errors. Out-of-memory and failed
   database connections are the two most likely.
3. Restart the service first. Many failures clear on restart.
4. If a recent deploy caused it, open the three-dot menu on the last working
   deployment and roll back to it.
5. If it crashes again right after starting, the cause is almost always a bad
   environment variable or an unreachable database. Go to §5.3.

### 5.3 Supabase — database unreachable

1. Supabase dashboard → the Unis project. Is it **paused**? On the Free plan a
   project pauses after 7 days of inactivity and must be resumed by hand.
   (This risk disappears on Pro.)
2. Check whether the database is out of storage. The Free plan caps at 500 MB,
   and a full disk looks like a total outage.
3. Check connection count. Too many open connections refuse new ones. Restarting
   the Railway backend clears its pool.
4. If the database is reachable but the **data is wrong or missing** — a bad
   migration, an accidental delete — go to §6.

### 5.4 Cloudflare R2 — media won't load

1. Cloudflare dashboard → R2 → the media bucket. Confirm the bucket and its
   files still exist.
2. Check whether the public access setting or custom domain changed.
3. Check whether the R2 API credentials used by the backend were rotated or
   revoked.
4. Audio and images are **not** in the database backups. If files are deleted
   from R2, they are gone. See §8.

### 5.5 Everything up but something is broken

Check the third parties the feature depends on:

- Payments failing → Stripe dashboard, status.stripe.com
- Emails not arriving → Resend dashboard
- SMS not arriving → Twilio console

### 5.6 Was it down over midnight Eastern?

Scheduled jobs run inside the backend around midnight Eastern: daily, weekly and
monthly awards, territory rank, and the monthly supported-artist job. If the
backend was down across midnight, those runs were **skipped, not delayed**.

After any outage spanning midnight, confirm whether that day's awards and ranks
were produced, and re-run or correct them by hand if not.

---

## 6. Restoring the database from backup

Use this only when data is lost or corrupted, not for an ordinary outage.

**Before restoring, stop and think.** A restore replaces current data with
yesterday's. If the live database is merely unreachable, restoring would throw
away real data for nothing.

### Step 1 — capture the current state first

Even a damaged database is evidence. Take a fresh dump of it before overwriting
anything, so a bad decision can be undone.

### Step 2 — pick the backup

Cloudflare → R2 → `unis-db-backups` → Objects → `daily/`. Files are named by
date and time. Choose the newest one from **before** the damage occurred.

### Step 3 — restore into the drill project first

Run the **Restore drill** workflow in GitHub with that backup's name instead of
`latest`. Confirm in the drill project that the data is intact and the damage is
absent. Never restore an unverified backup straight into production.

### Step 4 — restore into production

Only after step 3 passes. Put the site into maintenance, restore, verify row
counts, then bring it back.

### Step 5 — write down what happened

Date, what broke, what was lost, how long it took, what would have prevented it.

---

## 7. Keeping this plan honest

| Task | How often | How |
|---|---|---|
| Restore drill | Monthly | Run the **Restore drill** workflow; record the time in §2 |
| Check the backup is recent | Weekly glance | Healthchecks.io shows the last ping |
| Test that alerts arrive | Quarterly | Break the R2 monitor's URL briefly, confirm the alert lands |
| Review this document | Quarterly, and after any infrastructure change | Re-read and correct |

**Note on the drill project:** it is a Free-plan Supabase project and pauses
after 7 days of inactivity. Running the drill monthly means it will usually be
paused and must be resumed in the dashboard before the drill will run.

---

## 8. Known gaps

Accepted for now, with the condition that should close each one.

1. **Media files have no second copy.** Audio and images exist only in R2.
   Cloudflare protects against hardware failure, not against a bug or a leaked
   key deleting files. Close this when artist uploads are hard to replace.
2. **Backups are once a day.** Up to 24 hours of data could be lost. Narrow this
   with Supabase Pro backups, and later point-in-time recovery, once real money
   is flowing through the platform.
3. **No staging environment.** Code goes from local to production. Close this
   before the first outside contributor, or after the first deploy-caused
   outage.
4. **One person responds to everything.** There is no second contact. Document
   who to call before taking any time fully offline.
5. **Backups are not encrypted beyond the storage provider's own encryption.**
   Acceptable while the bucket is private and tightly scoped. Revisit when the
   database holds payout details at volume.

---

## 9. Where everything lives

| Thing | Where |
|---|---|
| Web app code | github.com/cyberstizz/unis-sample |
| Backend code | github.com/cyberstizz/unis_mvp |
| Backup workflow | `.github/workflows/daily-db-backup.yml` in unis_mvp |
| Restore drill workflow | `.github/workflows/restore-drill.yml` in unis_mvp |
| Backup files | Cloudflare R2, bucket `unis-db-backups`, prefix `daily/`, kept 30 days |
| Backup/restore credentials | GitHub repository secrets on unis_mvp |
| Uptime monitoring | UptimeRobot |
| Backup monitoring | Healthchecks.io |
