"""Guards on docker-compose.prod.yml (ADR-0085): the whole stack on one public server, read from the real resolved configuration (needs Docker, skipped without it).

Run: python -m pytest docker/checks
"""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
FILES = ["-f", "docker-compose.yml", "-f", "docker-compose.app.yml", "-f", "docker-compose.prod.yml"]
NEEDED = ["SKYHUB_PII_KEY", "SKYHUB_MANAGE_TOKEN_KEY", "SKYHUB_BOARDING_PASS_KEY", "SKYHUB_LINK_TOKEN_KEY",
          "SKYHUB_AGENT_API_TOKEN", "SKYHUB_AGENT_USERNAME", "SKYHUB_AGENT_PASSWORD", "GRAFANA_ADMIN_PASSWORD",
          "SKYHUB_BOOTSTRAP_ADMIN_USERNAME", "SKYHUB_BOOTSTRAP_ADMIN_PASSWORD",
          "SKYHUB_DOMAIN", "SKYHUB_SITE_URL", "POSTGRES_PASSWORD"]
JAVA = ["booking", "flight-search", "notification"]

pytestmark = pytest.mark.skipif(shutil.which("docker") is None, reason="needs Docker")


def compose(tmp_path: Path, values: dict[str, str], *args: str) -> subprocess.CompletedProcess:
    env_file = tmp_path / "test.env"
    env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
    command = ["docker", "compose", "--env-file", str(env_file), *FILES, "config", *args]
    return subprocess.run(command, cwd=ROOT, capture_output=True, text=True)


@pytest.fixture
def config(tmp_path):
    result = compose(tmp_path, {name: f"SENTINEL-{name}" for name in NEEDED}, "--format", "json")
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)["services"]


def published(service: dict) -> list[tuple[str, str]]:
    return [(p.get("host_ip", ""), str(p["published"])) for p in service.get("ports", [])]


def test_only_the_web_server_listens_on_the_internet_and_only_on_80_and_443(config):
    open_to_all = {name: ports for name, s in config.items()
                   if (ports := [port for ip, port in published(s) if ip not in ("127.0.0.1", "::1")])}
    assert open_to_all == {"caddy": ["80", "443", "443"]}


def test_the_database_redis_and_kafka_are_not_published_at_all_not_even_on_the_server_itself(config):
    assert {name: published(config[name]) for name in ("postgres", "redis", "kafka")} == \
           {"postgres": [], "redis": [], "kafka": []}


def test_the_mail_inbox_is_reachable_only_from_the_server_itself_through_an_ssh_tunnel(config):
    assert published(config["mailpit"]) == [("127.0.0.1", "8025")]


def test_the_website_is_reached_through_the_web_server_not_directly(config):
    assert published(config["website"]) == []
    assert config["caddy"]["depends_on"]["website"]["condition"] == "service_healthy"


def test_rate_limits_count_each_visitor_not_everyone_as_one_because_all_requests_now_come_from_caddy(config):
    assert config["website"]["environment"]["SKYHUB_TRUST_PROXY"] == "true"


def test_the_database_password_comes_from_the_environment_and_every_service_uses_it(config):
    assert config["postgres"]["environment"]["POSTGRES_PASSWORD"] == "SENTINEL-POSTGRES_PASSWORD"
    assert {name: config[name]["environment"]["DB_PASSWORD"] for name in JAVA} == \
           {name: "SENTINEL-POSTGRES_PASSWORD" for name in JAVA}


def test_emailed_links_point_at_the_public_site_not_at_localhost(config):
    assert config["notification"]["environment"]["SKYHUB_SITE_URL"] == "SENTINEL-SKYHUB_SITE_URL"


def test_the_web_server_is_told_its_domain_so_it_can_fetch_a_certificate_for_it(config):
    assert config["caddy"]["environment"]["SKYHUB_DOMAIN"] == "SENTINEL-SKYHUB_DOMAIN"
    assert (ROOT / "deploy" / "Caddyfile").exists()


def test_each_java_service_has_a_memory_ceiling_so_three_heaps_cannot_eat_the_server(config):
    # without a limit each JVM sizes its heap from the whole machine (MaxRAMPercentage=75 in the image)
    assert [name for name in JAVA if not config[name].get("mem_limit")] == []


def test_everything_comes_back_by_itself_after_the_server_reboots(config):
    assert [name for name, s in config.items() if s.get("restart") not in ("unless-stopped", "always")
            and not s.get("profiles")] == []


@pytest.mark.parametrize("missing", ["SKYHUB_DOMAIN", "SKYHUB_SITE_URL", "POSTGRES_PASSWORD"])
def test_it_will_not_start_on_a_server_without_its_domain_site_address_or_database_password(tmp_path, missing):
    values = {name: f"SENTINEL-{name}" for name in NEEDED if name != missing}

    result = compose(tmp_path, values, "-q")

    assert result.returncode != 0 and missing in result.stderr
