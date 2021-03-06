#!/usr/bin/env python

"""dockerize.py

Usage:
    dockerize.py [options]

Options:
    -h --help       Show this screen.
    --push          DO push image tags to a remote Docker repository.
    --version       The version of this program.

"""

import os
import shlex

from subprocess import call, Popen, PIPE, STDOUT


def check_semver(text):
    import semantic_version
    return semantic_version.Version(text)


def cmd(args, print_stdout=True):
    command = shlex.split(args)

    process = Popen(command, stdout=PIPE, stderr=STDOUT)
    lines = []
    while True:
        output = process.stdout.readline()
        if output == '' and process.poll() is not None:
            break
        if output:
            line = output.strip()
            if print_stdout:
                print(" | " + line)
            lines.append(line)

    rc = process.poll()
    return rc, '\n'.join(lines)


def docker(args):
    cmd_args = "docker {}".format(args)
    (rc, out) = cmd(cmd_args)
    if rc != 0:
        raise ValueError("""Error! The `docker` command failed (exit code: {})

The command was: {}
        """.format(rc, cmd_args))


def gradle(args):
    return cmd("./gradlew {}".format(args), print_stdout=False)[1].rstrip()


def handle_git_tag(repo, git_commit, git_tag):

    """Tag a Docker image.
    
    The tag of the Docker image will be the same as the Git tag. The Git tag must be semantic version ("semver") 
    compliant otherwise an error will be raised.
    
    A new Docker image is *NOT* built for the tag rather it is assumed that the tag points to a Docker image for the
    previous commit.
    """

    if git_tag == 'latest':
        raise ValueError('Invalid tag name "latest" not allowed!')

    check_semver(git_tag)

    print("==> Triggered from Tag <{0}>: Pulling Docker image associated with the tagged commit and adding "
          + "additional tag for version (version: {0})".format(git_tag))

    # If we're tagging an image it *should* have had a previous Docker build and push at some point in its history and
    # therefore we do not need to rebuild the image.
    docker("pull {0}:{1}".format(repo, git_commit))
    docker("tag  {0}:{1} {0}:{2}".format(repo, git_commit, git_tag))


def main(args):
    branch = os.getenv('TRAVIS_BRANCH').replace('/', '_') if os.getenv('TRAVIS_BRANCH') else None
    docker_repo = os.getenv('DOCKER_REPO')
    commit = os.getenv('COMMIT', os.getenv('TRAVIS_COMMIT'))
    version = gradle('--quiet version')
    is_tag = os.getenv('TRAVIS_TAG') not in ['', None]
    is_pull_request = os.getenv('TRAVIS_PULL_REQUEST', 'false') != 'false'
    pull_request_number = os.getenv('TRAVIS_PULL_REQUEST')
    build_number = os.getenv('TRAVIS_BUILD_NUMBER')

    if is_pull_request:
        print("==> Triggered from GitHub PR <{}>: Docker image WILL NOT be built".format(pull_request_number))
    elif is_tag:
        handle_git_tag(docker_repo, commit, version)
    else:
        # Version is "latest" for master branch builds coming off Travis. If a build occurs on a branch then it will
        # be the branch name.
        tags = ['-t {0}:{1}'.format(docker_repo, version)]
        if commit:
            tags.append('-t {0}:{1}'.format(docker_repo, commit))

        if build_number:
            tags.append('-t {0}:travis-{1}'.format(docker_repo, build_number))

        tags = ' '.join(tags)

        if branch:
            print("==> Triggered from Push <{}>: Docker image WILL be built".format(branch))

        docker('build {0} --build-arg IMPL_VERSION={1} .'.format(tags, version))

    if not is_pull_request and args['--push']:
        docker('push {}'.format(docker_repo))
    else:
        print("==> Skipping Docker image push")
        return


if __name__ == '__main__':
    from docopt import docopt

    exit(main(docopt(__doc__)))
