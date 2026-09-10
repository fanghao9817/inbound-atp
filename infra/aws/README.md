# infra/aws — serverless storefront projection (SAM / CloudFormation)

Two stacks, both in us-west-2:

| Stack | Template | Deployed by | Contains |
|---|---|---|---|
| `inbound-atp-bootstrap` | `bootstrap.yaml` | an administrator, once | artifact S3 bucket, GitHub OIDC provider, `inbound-atp-github-deploy` role scoped to `inbound-atp-*` resources |
| `inbound-atp` | `template.yaml` (SAM) | GitHub Actions via the OIDC role (or `scripts/deploy-aws.sh`) | DynamoDB `inbound-atp-availability` (provisioned 5/5), `inbound-atp-projector` Lambda, `inbound-atp-availability-api` Lambda + Function URL, 7-day log groups, `inbound-atp-box` IAM user (invoke-only) |

```bash
AWS_PROFILE_NAME=deployer scripts/deploy-aws.sh bootstrap   # once
AWS_PROFILE_NAME=deployer scripts/deploy-aws.sh             # package + deploy the app stack, print outputs
```

Data model (single table): `pk = SKU#<code>`, `sk = FC#<code>`, attributes `availableNow`, `promiseDate`,
`promisable`, `confidence`, `nextArrival`, `updatedAt`, `source`. Writes are conditional on `updatedAt`
so a stale projection never overwrites a newer one.
