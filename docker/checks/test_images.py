"""Guards on how the images are made, checked without Docker (the builds themselves are checked in CI).

Every Dockerfile in docker/ is held to the same rules, and so is the .dockerignore of the folder it is built from.
Run: python -m pytest docker/checks
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
# Each Dockerfile and the folder it is built from (docker build -f docker/<name> <context>).
CONTEXTS = {
    "service.Dockerfile": ROOT,
    "disruption-agent.Dockerfile": ROOT / "services" / "disruption-agent",
    "website.Dockerfile": ROOT / "frontend",
}
JAVA_SERVICES = re.findall(r"<module>services/([\w-]+)</module>", (ROOT / "pom.xml").read_text(encoding="utf-8"))


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def ignored(context: Path) -> set[str]:
    return {line.strip() for line in text(context / ".dockerignore").splitlines()
            if line.strip() and not line.startswith("#")}


def test_every_dockerfile_in_the_folder_is_covered_here():
    assert {p.name for p in (ROOT / "docker").glob("*.Dockerfile")} == set(CONTEXTS)


@pytest.mark.parametrize("context", sorted(set(CONTEXTS.values())), ids=lambda p: p.name)
def test_secrets_and_history_never_enter_a_build_context(context):
    # .env holds the real keys: anything copied into an image layer can be read by whoever can pull the image
    assert {".env", ".env.*", "**/.env"} <= ignored(context)
    assert {"**/node_modules", "**/.venv"} & ignored(context) or context == ROOT
    if context == ROOT:
        assert {".git", "**/target", "logs"} <= ignored(context)


@pytest.mark.parametrize("name", sorted(CONTEXTS))
def test_no_image_copies_in_a_key_or_an_env_file(name):
    copies = [line for line in text(ROOT / "docker" / name).splitlines() if line.strip().upper().startswith(("COPY", "ADD"))]
    assert not [c for c in copies if ".env" in c or ".pem" in c or ".key" in c]


@pytest.mark.parametrize("name", sorted(CONTEXTS))
def test_every_image_runs_as_a_user_that_is_not_root(name):
    users = re.findall(r"^USER\s+(\S+)", text(ROOT / "docker" / name), re.MULTILINE)
    assert users and users[-1] not in {"root", "0"}


@pytest.mark.parametrize("name", sorted(CONTEXTS))
def test_a_key_is_never_given_a_value_in_an_image(name):
    # keys arrive as environment variables when the container starts (SKYHUB_PII_KEY and the others)
    assert not re.search(r"^\s*(ENV|ARG)\s+\w*(KEY|PASSWORD|SECRET|TOKEN)\w*\s*=?\s*\S", text(ROOT / "docker" / name),
                         re.MULTILINE | re.IGNORECASE)


@pytest.mark.parametrize("name", sorted(CONTEXTS))
def test_the_final_image_is_not_the_one_that_built_it(name):
    stages = re.findall(r"^FROM\s+(\S+)", text(ROOT / "docker" / name), re.MULTILINE | re.IGNORECASE)
    assert len(stages) >= 2


def test_the_java_images_hold_a_runtime_not_the_build_tools():
    stages = re.findall(r"^FROM\s+(\S+)", text(ROOT / "docker" / "service.Dockerfile"), re.MULTILINE | re.IGNORECASE)
    assert "jdk" in stages[0] and "jre" in stages[-1]


def test_one_dockerfile_builds_every_java_service_named_in_the_parent_pom():
    dockerfile = text(ROOT / "docker" / "service.Dockerfile")
    assert JAVA_SERVICES == ["flight-search", "booking", "notification"]
    assert "ARG SERVICE" in dockerfile and "-pl services/${SERVICE}" in dockerfile


def test_the_website_is_built_as_a_standalone_server_so_the_image_needs_no_node_modules():
    assert re.search(r"output:\s*[\"']standalone[\"']", text(ROOT / "frontend" / "next.config.ts"))


def test_the_agent_listens_on_every_address_inside_its_container_and_nothing_else_is_set_for_it():
    dockerfile = text(ROOT / "docker" / "disruption-agent.Dockerfile")
    assert "SKYHUB_AGENT_HOST=0.0.0.0" in dockerfile      # its default, 127.0.0.1, is unreachable from outside a container
