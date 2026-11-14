# Deploying SkyHub on a server (Hetzner, DigitalOcean or AWS)

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

### Or on DigitalOcean

The 8 GB size costs about $48 a month. The GitHub Student Developer Pack has offered DigitalOcean credit in the past, but not always: check the Pack before counting on it. At <https://cloud.digitalocean.com>:

1. **Settings, Billing, Billing alerts**: set an alert at about $50, so a forgotten server cannot quietly use up the credit.
2. **Create, Droplets**:

   | Setting | Choose |
   |---|---|
   | Region | The one closest to your visitors: **Bangalore** (BLR1) for the Gulf and South Asia, or **Frankfurt** (FRA1) |
   | Image | **OS, Ubuntu 24.04 (LTS) x64** |
   | Size | **Basic, Regular**, **8 GB / 4 CPUs** (about $48 a month). 4 GB is not enough |
   | Authentication | **SSH Key, New SSH Key**: paste the line from step 1, name it `my-pc` |
   | Monitoring | Tick the free **metrics monitoring** (CPU, memory and disk graphs in the console) |
   | Backups | Off for a demo |
   | Hostname | `skyhub` |

   **Create Droplet**. Its **ipv4** address appears in a minute. It stays the same for as long as the Droplet exists, even when it is switched off.
3. **Networking, Firewalls, Create Firewall** named `skyhub-web`. Inbound: **SSH** (TCP 22), **HTTP** (TCP 80), **HTTPS** (TCP 443) and a **Custom** rule for **UDP 443**, all from all IPv4 and IPv6. Leave the outbound rules as they are. Under **Apply to Droplets** choose `skyhub`, then **Create Firewall**.

You log in as `root`, as on Hetzner, so everything from step 3 on is the same. A switched-off Droplet is still billed: **Destroy** it to stop the cost.

### Or on AWS (EC2)

A new AWS account comes with free credits (about $100 at sign-up and more for finishing the starter tasks; check the sign-up page for the current amounts). An 8 GB `t3.large` with its disk and address costs about $65-75 a month, so the credits cover two to three months. At <https://console.aws.amazon.com>:

1. **Protect the account first.** Account name (top right), **Security credentials**, **Assign MFA device**, with an authenticator app.
2. **Budgets, Create budget, Monthly cost budget**: $20 with your email. AWS does not stop spending on its own.
3. **Pick the region** in the menu at the top right, for example **Middle East (UAE) `me-central-1`** or **Europe (Frankfurt)**. Every later step happens in that region; if the server seems to have vanished, check this menu first.
4. **EC2, Launch instance**:

   | Setting | Choose |
   |---|---|
   | Name | `skyhub` |
   | Image | **Ubuntu Server 24.04 LTS**, **64-bit (x86)** |
   | Instance type | **t3.large** (2 vCPU, 8 GiB). Not micro or small. If a Free plan account refuses it, upgrade to the Paid plan: the credits still pay first, but the card is charged once they are gone |
   | Key pair | **Create new key pair**: `skyhub-key`, **ED25519**, **.pem**. The file downloads once; keep it |
   | Network settings, **Edit** | Auto-assign public IP **on**; **Create security group** `skyhub-web` with inbound **SSH 22 from My IP**, **HTTP 80** and **HTTPS 443** from anywhere, and **Custom UDP 443** from anywhere |
   | Storage | **30 GiB gp3** (the default 8 is too small for the images) |

   **Launch instance**, then wait for **Running** and **2/2 checks passed**.
5. **EC2, Elastic IPs, Allocate**, then **Actions, Associate** it with `skyhub`. Without it the address changes every time the server is stopped and started. Use this address from now on.
6. Log in from PowerShell (Windows refuses a key that others can read, hence `icacls`):
   ```powershell
   Move-Item $HOME\Downloads\skyhub-key.pem $HOME\.ssh\
   icacls $HOME\.ssh\skyhub-key.pem /inheritance:r /grant:r "$($env:USERNAME):R"
   ssh -i $HOME\.ssh\skyhub-key.pem ubuntu@YOUR_ELASTIC_IP
   ```

**What differs from the rest of this guide on AWS:**

- You log in as **`ubuntu`**, not `root`, so step 4 needs `sudo`:
  ```bash
  sudo apt update && sudo apt -y upgrade
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker ubuntu          # docker without sudo; log out and in again for it to apply
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```
- Every `ssh` and `scp` command takes the key: `ssh -i $HOME\.ssh\skyhub-key.pem ubuntu@IP`, and the `.env` lives in `/home/ubuntu/SkyHub`.
- If SSH suddenly times out, your home IP has changed: set the SSH rule of `skyhub-web` to **My IP** again.
- **Stop** the instance and compute billing stops, but the disk and the Elastic IP are still charged. To stop everything: **Terminate** the instance, then **Release** the Elastic IP. Do it before the credits run out.

The cloud firewall sits in front of the server. The compose file does not rely on it: it publishes no port except Caddy's (Docker goes around a firewall *on* the server, such as ufw, which is why the ports are removed instead).

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
