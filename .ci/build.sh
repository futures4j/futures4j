#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Sebastian Thomschke
# SPDX-FileContributor: Sebastian Thomschke: initial configuration
# SPDX-License-Identifier: EPL-2.0

#####################
# Script init
#####################
set -eu

# execute script with bash if loaded with other shell interpreter
if [ -z "${BASH_VERSINFO:-}" ]; then /usr/bin/env bash "$0" "$@"; exit; fi

set -o pipefail

# configure stack trace reporting
trap 'rc=$?; echo >&2 "$(date +%H:%M:%S) Error - exited with status $rc in [$BASH_SOURCE] at line $LINENO:"; cat -n $BASH_SOURCE | tail -n+$((LINENO - 3)) | head -n7' ERR

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


#####################
# Main
#####################

if [[ -f .ci/release-trigger.sh ]]; then
  echo "Sourcing [.ci/release-trigger.sh]..."
  source .ci/release-trigger.sh
fi

cd $(dirname $0)/..

echo
echo "###################################################"
echo "# Determining GIT branch......                    #"
echo "###################################################"
GIT_BRANCH=$(git branch --show-current)
echo "  -> GIT Branch: $GIT_BRANCH"


echo
echo "###################################################"
echo "# Configuring MAVEN_OPTS...                       #"
echo "###################################################"
MAVEN_OPTS="${MAVEN_OPTS:-}"
MAVEN_OPTS+=" -Djava.security.egd=file:/dev/./urandom" # https://stackoverflow.com/questions/58991966/what-java-security-egd-option-is-for/59097932#59097932
MAVEN_OPTS+=" -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS" # https://stackoverflow.com/questions/5120470/how-to-time-the-different-stages-of-maven-execution/49494561#49494561
MAVEN_OPTS+=" -Xmx1024m -Djava.awt.headless=true -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.3,TLSv1.2"
echo "  -> MAVEN_OPTS: $MAVEN_OPTS"
export MAVEN_OPTS

MAVEN_CLI_OPTS="-e -U --batch-mode --show-version -s .ci/maven-settings.xml -t .ci/maven-toolchains.xml"
if [[ -n ${GITEA_ACTIONS:-} || (-n ${CI:-} && -z ${ACT:-}) ]]; then # if running on a remote CI but not on local nektos/act runner
  MAVEN_CLI_OPTS+=" --no-transfer-progress"
fi
if [[ -n ${ACT:-} ]]; then
  MAVEN_CLI_OPTS+=" -Dformatter.validate.lineending=KEEP"
fi
if [[ ${MAY_CREATE_RELEASE:-false} == "true" && ${GITHUB_ACTIONS:-} == "true" ]]; then
  MAVEN_CLI_OPTS+=" -Dskip.maven.javadoc=false"
fi
echo "  -> MAVEN_CLI_OPTS: $MAVEN_CLI_OPTS"


echo
echo "###################################################"
echo "# Determining current Maven project version...    #"
echo "###################################################"
# https://stackoverflow.com/questions/3545292/how-to-get-maven-project-version-to-the-bash-command-line
projectVersion=$(python -c "import xml.etree.ElementTree as ET; \
  print(ET.parse(open('pom.xml')).getroot().find(  \
  '{http://maven.apache.org/POM/4.0.0}version').text)")
echo "  -> Current Version: $projectVersion"
if [[ ${GITHUB_ACTIONS:-} == "true" ]]; then
  echo "MAVEN_PROJECT_VERSION=$projectVersion" >> $GITHUB_ENV
fi


#
# ensure mnvw is executable
#
chmod u+x ./mvnw


#
# set github author for commits during release and site builds
#
if [[ ${MAY_CREATE_RELEASE:-false} == "true" && ${GITHUB_ACTIONS:-} == "true" ]]; then
  # https://github.community/t/github-actions-bot-email-address/17204
  git config --global user.name "github-actions[bot]"
  git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
fi


#
# determine if this is a release build
#
if [[ ${projectVersion:-foo} == ${POM_CURRENT_VERSION:-bar} && ${MAY_CREATE_RELEASE:-false} == "true" ]]; then
  # https://stackoverflow.com/questions/8653126/how-to-increment-version-number-in-a-shell-script/21493080#21493080
  nextDevelopmentVersion="$(echo ${POM_RELEASE_VERSION} | perl -pe 's/^((\d+\.)*)(\d+)(.*)$/$1.($3+1).$4/e')-SNAPSHOT"

  SKIP_TESTS=${SKIP_TESTS:-false}

  echo
  echo "###################################################"
  echo "# Creating Maven Release...                       #"
  echo "###################################################"
  echo "  ->          Release Version: ${POM_RELEASE_VERSION}"
  echo "  -> Next Development Version: ${nextDevelopmentVersion}"
  echo "  ->           Skipping Tests: ${SKIP_TESTS}"
  echo "  ->               Is Dry-Run: ${DRY_RUN}"

  # workaround for "No toolchain found with specification [version:11, vendor:default]" during release builds
  cp -f .ci/maven-settings.xml $HOME/.m2/settings.xml
  cp -f .ci/maven-toolchains.xml $HOME/.m2/toolchains.xml

  export DEPLOY_RELEASES_TO_MAVEN_CENTRAL=true

  ./mvnw $MAVEN_CLI_OPTS "$@" \
      -DskipTests=${SKIP_TESTS} \
      -DskipITs=${SKIP_TESTS} \
      -DdryRun=${DRY_RUN} \
      -Dresume=false \
      "-Darguments=-DskipTests=${SKIP_TESTS} -DskipITs=${SKIP_TESTS}" \
      -DreleaseVersion=${POM_RELEASE_VERSION} \
      -DdevelopmentVersion=${nextDevelopmentVersion} \
      help:active-profiles clean release:clean release:prepare release:perform \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
  exit $?
fi


#
# build/deploy snapshot version
#
SNAPSHOTS_BRANCH="${SNAPSHOTS_BRANCH:-mvn-snapshots-repo}"
JAVADOC_BRANCH="${JAVADOC_BRANCH:-javadoc}"

if [[ ${MAY_CREATE_RELEASE:-false} == "true" ]]; then

  MAVEN_GOAL="deploy"

  if [[ ${GITHUB_ACTIONS:-} == "true" ]]; then

    function initializeSiteBranch() {
      while [[ $# -gt 0 ]]; do
        case $1 in
          --branch) local branch="$2"; shift 2 ;;
          --revert-last-commit) local revert_last_commit=true; shift 1 ;;
          *)        echo "Unknown parameter: $1"; return 1 ;;
        esac
      done

      pushd /tmp
        rm -rf "$branch"
        github_repo_url="https://${GITHUB_USER}:${GITHUB_API_KEY}@github.com/${GITHUB_REPOSITORY}"
        if curl --output /dev/null --silent --head --fail "$github_repo_url/tree/$branch"; then
          git clone "$github_repo_url" --single-branch --branch $branch $branch
          pushd $branch >/dev/null
          if [[ "${revert_last_commit:-false}" == "true" ]]; then
            git reset --hard HEAD^ # revert previous commit
          fi
        else
          git clone "$github_repo_url" $branch
          pushd $branch >/dev/null
          git checkout --orphan $branch
          git rm -rf .
          touch .gitkeep
          git add .gitkeep
          git commit -am "Initial commit"
        fi
        popd >/dev/null
      popd >/dev/null
    }

    last_commit_message=$(git log --pretty=format:"%s (%h)" -1)

    echo
    echo "###################################################"
    echo "# Preparing $SNAPSHOTS_BRANCH branch...       #"
    echo "###################################################"
    initializeSiteBranch --branch $SNAPSHOTS_BRANCH
    MAVEN_GOAL+=" -DaltSnapshotDeploymentRepository=temp-snapshots-repo::file:///tmp/$SNAPSHOTS_BRANCH/"

    echo
    echo "###################################################"
    echo "# Preparing $JAVADOC_BRANCH branch...            #"
    echo "###################################################"
    initializeSiteBranch --branch $JAVADOC_BRANCH --revert-last-commit
    rm -rf target/*-javadoc.jar target/reports/apidocs
    MAVEN_GOAL+=" -Dskip.maven.javadoc=false"
  fi
else
  MAVEN_GOAL="verify"
fi

echo
echo "###################################################"
echo "# Building Maven Project...                       #"
echo "###################################################"
./mvnw $MAVEN_CLI_OPTS "$@" \
    help:active-profiles clean $MAVEN_GOAL \
    | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms

if [[ ${MAY_CREATE_RELEASE:-false} == "true" && ${GITHUB_ACTIONS:-} == "true" ]]; then
  echo
  echo "###################################################"
  echo "# Update Maven Snapshots Repo...                  #"
  echo "###################################################"
  pushd /tmp/$SNAPSHOTS_BRANCH >/dev/null
    cat <<EOF > index.html
<!DOCTYPE html>
<html lang="en">
<head>
  <title>${GITHUB_REPOSITORY} - Maven Snapshots Repo</title>
</head>
<body>
  <h1>${GITHUB_REPOSITORY} - Maven Snapshots Repo</h1>
</body>
</html>
EOF
    if [[ $(git -C . ls-files -o -m -d --exclude-standard | wc -l) -gt 0 ]]; then
      git add --all
      git commit -am "$projectVersion: $last_commit_message"
      git push origin $SNAPSHOTS_BRANCH --force
    fi
  popd >/dev/null

  echo
  echo "###################################################"
  echo "# Deploying Javadoc...                            #"
  echo "###################################################"
  rm -rf /tmp/$JAVADOC_BRANCH/javadoc
  if [[ -f target/reports/apidocs/index.html ]]; then
    mv target/reports/apidocs /tmp/$JAVADOC_BRANCH/javadoc
  else
    mkdir /tmp/$JAVADOC_BRANCH/javadoc
    unzip "target/*-javadoc.jar" -d /tmp/$JAVADOC_BRANCH/javadoc
  fi
  pushd /tmp/$JAVADOC_BRANCH >/dev/null
    cat <<EOF > index.html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta http-equiv="refresh" content="0; url=/${GITHUB_REPOSITORY##*/}/javadoc/" />
    <title>Redirecting...</title>
</head>
<body>
    <p>If you are not redirected automatically, follow this <a href="/javadoc/">link to the /${GITHUB_REPOSITORY##*/}javadoc/</a>.</p>
</body>
</html>
EOF
    if [[ $(git -C . ls-files -o -m -d --exclude-standard | wc -l) -gt 0 ]]; then
      git add --all
      git commit -am "$projectVersion: $last_commit_message"
      git push origin $JAVADOC_BRANCH --force
    fi
  popd >/dev/null
fi
