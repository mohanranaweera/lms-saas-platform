# Infrastructure Instructions

This directory contains development and deployment infrastructure.

Use:
- Docker
- Docker Compose
- Nginx
- environment-based configuration

Never commit secrets.

Do not create one Docker stack per tenant.

Do not execute a production deployment without explicit human approval.

Local development infrastructure may use containers while frontend and backend run natively for faster development.

Production and staging will run containerized applications.
