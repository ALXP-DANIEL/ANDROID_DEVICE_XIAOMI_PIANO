# Build environment for Mu-Silicium based UEFI images.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    git build-essential lld uuid-dev nasm gcc-aarch64-linux-gnu python3 \
    python3-pip python3-venv python3-git mono-devel gettext locales gnupg \
    ca-certificates clang llvm curl device-tree-compiler \
 && rm -rf /var/lib/apt/lists/* && locale-gen en_US.UTF-8
COPY deps/Mu-Silicium/pip-requirements.txt /tmp/req.txt
RUN python3 -m venv /venv && /venv/bin/pip install -q -r /tmp/req.txt
ENV PATH=/venv/bin:$PATH LANG=en_US.UTF-8
WORKDIR /work
