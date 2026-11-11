# Deploying SkyHub on a Hetzner server

This puts the whole of SkyHub (website, three Java services, the AI agent, PostgreSQL, Redis, Kafka and the mail catcher) on **one Linux server** with HTTPS, at an address anyone can open. Why one server and not Vercel or Kubernetes is in [ADR-0085](adr/0085-one-server-with-caddy.md).

It takes about 30 minutes the first time, most of it waiting for the build. You need: a Hetzner account and a card, the Windows terminal (PowerShell) and, if you want a proper name, a domain.

```
Internet ──HTTPS──► Caddy (80, 443) ──► website ──► flight-search, booking, disruption-agent
                                                       └► PostgreSQL, Redis, Kafka, notification, Mailpit
                    only Caddy is reachable from outside; everything else talks on Docker's private network
```

---

## 1. Make an SSH key on your PC (once)

The key is how you log in to the server, instead of a password. In PowerShell:

```powershell
ssh-keygen -t ed25519            # press Enter for every question (a passphrase is optional but better)
Get-Content $HOME\.ssh\id_ed25519.pub
```

Copy the line it prints (it starts with `ssh-ed25519`). That is the **public** half and is safe to share. Never share `id_ed25519` (without `.pub`).

## 2. Create the server

At <https://console.hetzner.cloud>: create a **Project** (for example `skyhub`), then **Add Server**:

| Setting | Choose |
|---|---|
| Location | Any. Nuremberg, Falkenstein or Helsinki are the cheapest; all are fine for a demo |
| Image | **Ubuntu 24.04** |
| Type | **Shared vCPU, x86, 8 GB RAM** (4 vCPU / 8 GB, around €7-8 a month). 4 GB is not enough: three Java services, Kafka and the build need the room |
| Networking | Public IPv4 **on** (a domain and most visitors need it), IPv6 on |
| SSH keys | **Add SSH key** and paste the line from step 1 |
| Firewalls | **Create Firewall**: inbound **TCP 22**, **TCP 80**, **TCP 443** and **UDP 443**, from any address. Nothing else |
| Name | `skyhub` |

**Create & Buy**. After a minute it shows the server's **IPv4 address**, for example `203.0.113.5`. Hetzner bills by the hour, so deleting the server stops the cost.

The Hetzner firewall sits in front of the server. The compose file does not rely on it: it publishes no port except Caddy's (Docker goes around a firewall *on* the server, such as ufw, which is why the ports are removed instead).

## 3. Give it a name

Pick one:

- **Your own domain** (about $10 a year at Cloudflare, Namecheap, Porkbun...). In its DNS settings add an **A record**: name `@` (or `skyhub` for `skyhub.yourdomain.com`), value the server's IPv4. Wait until `nslookup your-domain` in PowerShell answers with that IP (usually minutes).
- **Free, no sign-up:** `sslip.io` turns an IP into a name. For `203.0.113.5` use **`203-0-113-5.sslip.io`**. It works with HTTPS straight away. Fine for trying it out; a real domain looks better on a CV.

## 4. Log in and prepare the server

```powershell
ssh root@203.0.113.5          # your IP. Type "yes" the first time
```

You are now on the server (the prompt changes to `root@skyhub`). Run:

```bash
apt update && apt -y upgrade
curl -fsSL https://get.docker.com | sh          # Docker and the compose plugin

# 2 GB of swap: insurance against the build running out of memory
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

git clone https://github.com/sudeshkar/SkyHub.git
cd SkyHub
```

## 5. Create the keys

```bash
./deploy/make-env.sh your-domain          # for example ./deploy/make-env.sh 203-0-113-5.sslip.io
```

This writes `.env` with a fresh random value for every key and password, and prints the **admin password** for the operations page. Save it in your password manager.

**Back up `.env` to your PC now** (in a PowerShell window on your PC, not on the server):

```powershell
scp root@203.0.113.5:SkyHub/.env $HOME\skyhub-server.env
```

Without `SKYHUB_PII_KEY` the travellers' details in the database can never be read again. Never commit `.env` or paste it anywhere.

Optional: to let the real language model plan rebookings, edit `.env` (`nano .env`), set `GROQ_API_KEY` (free at <https://console.groq.com>) and `SKYHUB_AGENT_PLANNER=groq`. Without it the rule-based planner is used.

## 6. Start it

```bash
./deploy/compose.sh up -d --build
```

The first time builds five images and takes 10 to 15 minutes. Then check:

```bash
./deploy/compose.sh ps
```

Every line should say `healthy` (or `running` for Caddy, Mailpit, Redis). Java services take a minute after the build to become healthy.

Then, **once**, create the AI agent's staff account (the agent needs one to read bookings):

```bash
./deploy/create-agent-account.sh
```

## 7. Open it

Open **`https://your-domain`** in a browser. The first visit can take a few seconds while Caddy gets the certificate from Let's Encrypt.

- Search, choose seats, book with the test card, manage the booking.
- **`https://your-domain/ops`**: sign in as `admin` with the password from step 5. Record a cancellation, then open the rebooking assistant to see the agent's proposals.
- **The emails** go to Mailpit, never to real inboxes, so a visitor who types a real address receives nothing. To read them, open a tunnel from your PC:
  ```powershell
  ssh -L 8025:localhost:8025 root@203.0.113.5
  ```
  and while it is open, visit <http://localhost:8025> on your PC. The inbox is never public, because it shows what other visitors typed.

## Everyday commands (on the server, in `~/SkyHub`)

| To | Run |
|---|---|
| Deploy the latest code from GitHub | `git pull && ./deploy/compose.sh up -d --build` |
| See what is running | `./deploy/compose.sh ps` |
| Follow a service's log | `./deploy/compose.sh logs -f booking` (or `website`, `caddy`...) |
| Restart one service | `./deploy/compose.sh restart booking` |
| Stop everything (data is kept) | `./deploy/compose.sh down` |
| Back up the database to a file | `./deploy/compose.sh exec -T postgres pg_dumpall -U skyhub > backup-$(date +%F).sql` |
| Free disk taken by old builds | `docker system prune` (and `docker builder prune` for the build cache) |

**Never** run `down -v`: the `-v` deletes the volumes, which means the database, Kafka and the certificate.

## When something is wrong

| You see | Look at |
|---|---|
| The browser says the site cannot be reached | `./deploy/compose.sh ps`: is Caddy up? Is the Hetzner firewall attached to the server with 80 and 443 open? |
| A certificate warning, or Caddy's log says `challenge failed` | The name does not point at the server yet (`nslookup your-domain`), or port 80 is closed. Fix it and `./deploy/compose.sh restart caddy`. Let's Encrypt allows only a few failures an hour, so wait between tries |
| `502 Bad Gateway` | The website is not healthy yet, or crashed: `./deploy/compose.sh logs website` |
| A service keeps restarting | `./deploy/compose.sh logs --tail 100 booking`. `set it in .env` means a key is missing from `.env` |
| `exited with code 137` | Out of memory. Check `free -h`; the swap from step 4 should be on |
| The rebooking assistant says it cannot sign in | Run `./deploy/create-agent-account.sh` |

## What this is not

A **demonstration** server: one machine, one database, one Kafka broker, no automatic backups or monitoring outside the server. It is the right size for showing the system to people. Running an airline on it would need what ADR-0085 lists as not done.

The observability stack (Prometheus, Grafana, Loki, Tempo) is not started here, to keep the server small. It can be added later, behind a password.

To stop paying: delete the server in the Hetzner console (and its snapshots, if you made any).
