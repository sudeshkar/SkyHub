"""Guards on how the service images are made, checked without Docker (the build itself is checked in CI).

Run: python -m pytest docker/checks
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = (ROOT / "docker" / "service.Dockerfile").read_text(encoding="utf-8")
IGNORED = {line.strip() for line in (ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()
           if line.strip() and not line.startswith("#")}
JAVA_SERVICES = re.findall(r"<module>services/([\w-]+)</module>", (ROOT / "pom.xml").read_text(encoding="utf-8"))


def test_secrets_and_history_never_enter_the_build_context():
    # .env holds the real keys: anything copied into an image layer can be read by whoever can pull the image
    assert {".env", ".env.*", "**/.env", ".git", "**/node_modules", "**/.venv", "**/target", "logs"} <= IGNORED


def test_the_image_never_copies_a_key_file_or_an_env_file_in():
    copies = [line for line in DOCKERFILE.splitlines() if line.strip().upper().startswith(("COPY", "ADD"))]
    assert not [c for c in copies if ".env" in c or ".pem" in c or ".key" in c]


def test_the_service_runs_as_a_user_that_is_not_root():
    users = re.findall(r"^USER\s+(\S+)", DOCKERFILE, re.MULTILINE)
    assert users and users[-1] not in {"root", "0"}


def test_the_final_image_holds_a_java_runtime_not_the_build_tools():
    stages = re.findall(r"^FROM\s+(\S+)(?:\s+AS\s+(\w+))?", DOCKERFILE, re.MULTILINE | re.IGNORECASE)
    assert len(stages) >= 2 and "jdk" in stages[0][0] and "jre" in stages[-1][0]


def test_one_dockerfile_builds_every_java_service_named_in_the_parent_pom():
    assert JAVA_SERVICES == ["flight-search", "booking", "notification"]
    assert "ARG SERVICE" in DOCKERFILE and "-pl services/${SERVICE}" in DOCKERFILE


def test_a_key_is_never_given_a_value_in_the_image():
    # keys arrive as environment variables when the container starts (SKYHUB_PII_KEY and the others)
    assert not re.search(r"^\s*ENV\s+\w*(KEY|PASSWORD|SECRET|TOKEN)\w*\s*=?\s*\S", DOCKERFILE, re.MULTILINE | re.IGNORECASE)
