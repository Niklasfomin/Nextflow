#
#  Copyright 2013-2019, Centre for Genomic Regulation (CRG)
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

version ?= 1.1

build: dist/docker
	cp ../build/releases/nextflow*-all nextflow

release: build
	docker tag nextflow/nextflow:${version} nfomin/cws:${version}
	docker push nfomin/cws:${version}


# Static builds can now be found at:
#
#  Linux:   https://download.docker.com/linux/static
#  MacOS:   https://download.docker.com/mac/static
#  Windows: https://download.docker.com/win/static

dist/docker:
	mkdir -p dist/linux/amd64
	curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-17.09.0-ce.tgz | tar --strip-components=1 -xvz -C dist/linux/amd64
#	mkdir -p dist/linux/arm64
#	curl -fsSL https://download.docker.com/linux/static/stable/aarch64/docker-17.09.0-ce.tgz | tar --strip-components=1 -xvz -C dist/linux/arm64
