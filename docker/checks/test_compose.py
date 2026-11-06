"""Guards on docker-compose.app.yml (the five parts, added to the infrastructure file), read from the real resolved configuration (needs Docker, skipped without it).

Run: python -m pytest docker/checks
"""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
APP = ["booking", "flight-search", "notification", "disruption-agent", "website"]
NEEDED = ["SKYHUB_PII_KEY", "SKYHUB_MANAGE_TOKEN_KEY", "SKYHUB_BOARDING_PASS_KEY", "SKYHUB_LINK_TOKEN_KEY",
          "SKYHUB_AGENT_API_TOKEN", "SKYHUB_AGENT_USERNAME", "SKYHUB_AGENT_PASSWORD", "GRAFANA_ADMIN_PASSWORD",
          "SKYHUB_BOOTSTRAP_ADMIN_USERNAME", "SKYHUB_BOOTSTRAP_ADMIN_PASSWORD"]
SECRETISH = ("KEY", "TOKEN", "PASSWORD", "SECRET")

pytestmark = pytest.mark.skipif(shutil.which("docker") is None, reason="needs Docker")


def compose(tmp_path: Path, values: dict[str, str], *args: str, app: bool = True) -> subprocess.CompletedProcess:
    env_file = tmp_path / "test.env"
    env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
    files = ["-f", "docker-compose.yml", *(["-f", "docker-compose.app.yml"] if app else [])]
    command = ["docker", "compose", "--env-file", str(env_file), *files, "config", *args]
    return subprocess.run(command, cwd=ROOT, capture_output=True, text=True)


@pytest.fixture
def config(tmp_path):
    result = compose(tmp_path, {name: f"SENTINEL-{name}" for name in NEEDED}, "--format", "json")
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)["services"]


def test_the_file_adds_all_five_parts_and_the_infrastructure_they_need(config):
    assert set(APP) <= set(config) and {"postgres", "kafka", "redis", "mailpit"} <= set(config)


def test_every_part_says_how_to_tell_that_it_is_working(config):
    assert [name for name in APP if "healthcheck" not in config[name]] == []


def test_the_services_wait_for_the_database_and_the_broker_to_be_healthy_not_just_started(config):
    for name in ("booking", "flight-search", "notification"):
        needs = config[name]["depends_on"]
        assert needs["postgres"]["condition"] == needs["kafka"]["condition"] == "service_healthy", name


def test_no_key_password_or_token_is_written_into_the_file_it_comes_from_the_environment(config):
    for name in APP:
        for variable, value in config[name].get("environment", {}).items():
            if any(word in variable for word in SECRETISH):
                # an optional key (the Groq key) may be empty; a value that is there must have come from the environment
                assert value in ("", None) or str(value).startswith("SENTINEL-"), f"{name} {variable} is not taken from the environment"


def test_only_the_website_is_reachable_from_outside_the_other_four_are_behind_it(config):
    published = {name: [p["published"] for p in config[name].get("ports", [])] for name in APP}
    assert published["website"] == ["3000"]
    assert {name: ports for name, ports in published.items() if name != "website" and ports} == {}


def test_every_image_is_built_from_a_dockerfile_and_folder_that_exist(config):
    for name in APP:
        build = config[name]["build"]
        context = Path(build["context"])
        assert context.is_dir() and (context / ".dockerignore").exists(), f"{name}: no folder, or no .dockerignore in it"
        assert (context / build["dockerfile"]).exists() or Path(build["dockerfile"]).exists(), f"{name}: no such Dockerfile"


def test_it_will_not_start_with_a_key_missing_rather_than_start_with_an_empty_one(tmp_path):
    values = {name: f"SENTINEL-{name}" for name in NEEDED if name != "SKYHUB_PII_KEY"}

    result = compose(tmp_path, values, "-q")

    assert result.returncode != 0 and "SKYHUB_PII_KEY" in result.stderr


def test_the_infrastructure_alone_never_asks_for_the_apps_keys(tmp_path):
    # compose checks every required variable in a file whether or not its service is wanted, which is why the app is a second file
    assert compose(tmp_path, {"GRAFANA_ADMIN_PASSWORD": "x"}, "-q", app=False).returncode == 0
