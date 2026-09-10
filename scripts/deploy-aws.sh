#!/usr/bin/env bash
# Deploys the AWS side. `bootstrap` = the one-time admin stack; no argument = package + deploy the app stack.
# Auth: AWS_PROFILE_NAME=<profile> for a local profile, or ambient credentials (GitHub OIDC in CI).
set -euo pipefail
cd "$(dirname "$0")/../infra/aws"
REGION="${AWS_REGION:-us-west-2}"
P=(); [ -n "${AWS_PROFILE_NAME:-}" ] && P=(--profile "$AWS_PROFILE_NAME")
ACCOUNT=$(aws "${P[@]}" sts get-caller-identity --query Account --output text)

if [ "${1:-}" = "bootstrap" ]; then
  aws "${P[@]}" cloudformation deploy --region "$REGION" --stack-name inbound-atp-bootstrap \
    --template-file bootstrap.yaml --capabilities CAPABILITY_NAMED_IAM --no-fail-on-empty-changeset
  aws "${P[@]}" cloudformation describe-stacks --region "$REGION" --stack-name inbound-atp-bootstrap \
    --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output text
  exit 0
fi

BUCKET="inbound-atp-artifacts-${ACCOUNT}"
PACKAGED="$(mktemp /tmp/inbound-atp-packaged.XXXX.yaml)"
aws "${P[@]}" cloudformation package --region "$REGION" --template-file template.yaml \
  --s3-bucket "$BUCKET" --s3-prefix app --output-template-file "$PACKAGED" >/dev/null
aws "${P[@]}" cloudformation deploy --region "$REGION" --stack-name inbound-atp \
  --template-file "$PACKAGED" --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND --no-fail-on-empty-changeset
rm -f "$PACKAGED"
aws "${P[@]}" cloudformation describe-stacks --region "$REGION" --stack-name inbound-atp \
  --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output text
