Functional module list for the architecture document

This is the module map I would use in the final architecture.

1. Tenant & Institute Management

Required features:

Institute registration
Tenant approval workflow
Tenant profile
Tenant logo
Tenant colors
Tenant contact information
Tenant domain/subdomain
Tenant subscription plan
Tenant status: trial, active, suspended, cancelled
Tenant feature limits
Tenant usage tracking

This module is essential because the product is SaaS.

2. White-Labeling & Branding

Required features:

Custom LMS name
Custom logo
Custom color theme
Custom student portal branding
Custom teacher portal branding
Custom email templates
Custom SMS sender name if available
Custom domain
Tenant-specific login page
Tenant-specific public course pages

The reference product includes white-labeling and custom domain/BYOD as advanced features.

Recommended addition:

Branding preview panel
Theme presets
Light/dark mode support
Per-tenant favicon
Per-tenant certificate branding
Per-tenant invoice branding

3. Student Management

Required features:

Student registration
Manual student creation by admin/staff
Bulk student import
Student profile
Guardian/parent information
School/grade/stream fields
Student status
Enrollment history
Payment history
Attendance history
Exam history
Device history
Communication history

Recommended addition:

Student tags
Risk indicators
Inactive student detection
Outstanding payment reminders
Student timeline/activity feed

4. Teacher Management

Required features:

Teacher registration
Teacher approval
Teacher profile
Assigned courses
Revenue/commission settings
Teacher login
Teacher dashboard
Teacher activity tracking

Recommended addition:

Teacher availability
Teacher payout profile
Teacher performance analytics
Teacher-specific public profile page
Teacher assistant role

5. Staff Management

Your added requirement is important.

Required features:

Add multiple staff members
Separate staff logins
Role-based access
Activity logs
Permission management
Staff count by SaaS plan
Staff status
Password reset

Suggested roles:

Institute Owner
Teacher
Teacher Assistant
Finance Staff
Course Coordinator
Student Support
Content Manager
Exam Manager
Attendance Operator
Read-only Auditor

6. Course Management

Reference product confirms course creation with modules, lessons, course materials, students, sessions, questions, and teachers.

Required features:

Create course
Course category
Subject/stream/grade/year
Course modules
Lessons
Sessions
Pricing
Enrollment rules
Access duration
Course visibility
Teacher assignment
Course materials
Course reviews
Course status

Recommended addition:

Course landing page builder
Trial/free lesson support
Course bundle support
Course prerequisites
Course cloning
Course archive
SEO settings for public course pages

7. Learning Materials Management

Required features:

Upload PDFs
Upload images
Upload notes
Attach videos
Attach Zoom recordings
Attach YouTube/Vimeo videos
Organize materials by lesson/module/session
Set material visibility
Set expiry
Limit views/downloads
Watermark documents

Recommended addition:

Material versioning
Bulk upload
Folder structure
Drag-and-drop ordering
Student-specific protected downloads
Document analytics

8. Video & Session Protection

This is a core differentiator for your system.

Required features:

Secure video playback
View limits per video/session
Watch-time tracking
Session expiry
Student name watermark
Device restriction
Download protection
Signed playback URLs
Access token validation

Important note:

No system can fully prevent screen recording, but the platform can strongly discourage it using:

Visible student watermark
Dynamic watermark movement
Device authentication
Session logging
Playback token expiry
IP/device anomaly detection

Recommended addition:

Playback abuse detection
Concurrent session blocking
Video access audit logs
Suspicious activity alerts

9. Live Class / Zoom Management

Required features:

Tenant Zoom account integration
Schedule live classes
Generate unique Zoom join URLs
Prevent link sharing
Standardize participant names
Sync attendance
Manage recordings
Attach recording to lesson/session
Cloud storage tracking

Recommended addition:

Multiple Zoom accounts per tenant
Auto-create recurring meetings
Auto-import cloud recordings
Auto-convert recording into catch-up lesson
Live class reminder automation

10. Attendance Management

Required features:

Class/session attendance
Manual attendance marking
Zoom attendance sync
Student attendance report
Course attendance report
Teacher attendance report
Absent student alerts

Recommended addition:

QR attendance for physical classes
Smart card attendance
Late/early-leave tracking
Attendance-based access restrictions

The reference pricing also includes attendance as a core feature across plans.

11. Exam Management

Required features:

Create exams
Question bank
MCQ questions
Structured questions
Exam scheduling
Time limits
Auto marking
Manual marking
Results publishing
Student answer review
Exam analytics

Recommended addition:

Negative marking
Randomized questions
Question pools
Attempt limits
Anti-cheating controls
Rank lists
Paper discussion videos
Model paper library

The reference product lists exams as a major platform feature and unlimited exams in its pricing structure.

12. Payment Management

We will keep your accepted final architecture.

Required phase 1:

Platform collects all student payments centrally
Orders are tenant-aware
Payments are tenant-aware
Enrollments activate after payment confirmation
Manual payment slip approval
Payment history
Refund handling
Admin payment dashboard

Required phase 2:

Tutor/tenant settlements
Commission calculation
Gateway fee tracking
Settlement status
Export settlement report
Finance dashboard

Required phase 3:

Tenant-specific payment account
Tenant payment configuration
Payment routing by tenant

Required phase 4:

Split payment / marketplace model if gateway supports it

The reference product includes payments as a core feature and also includes smart payment slip analysis and smart payment expiry as advanced features.

13. Student Payments

Required features:

Student payment history
Outstanding payments
Upcoming payment reminders
Manual payment slip upload
Duplicate slip detection
Reference number extraction
Payment approval/rejection
Payment receipt generation
Course access activation
Payment expiry
Reactivation payment

Your duplicate payment slip protection requirement should become a dedicated submodule:

Payment Slip Intelligence Module

Features:

OCR reference extraction
Duplicate reference check
Duplicate image hash check
Manual override with audit log
Payment slip search
Suspicious slip flagging

14. Finance & Expenses Management

Required features:

Income dashboard
Expense dashboard
Category-wise expenses
Account-wise expenses
Multiple bank/cash accounts
Scheduled payments
Tutor payouts
Wallet transactions
Financial reports

Recommended addition:

Profit/loss report
Cashflow forecast
Expense approval workflow
Receipt attachment
Monthly closing process
Export to Excel/PDF

The reference pricing confirms expenses management as an advanced feature.

15. Communication Module

Your requirement mentions built-in email functionality across modules.

Required features:

Email notifications
SMS notifications
WhatsApp notifications
In-app notifications
Tenant-specific templates
Module-specific templates
Bulk messaging
Student segment messaging
Payment reminders
Class reminders
Exam reminders
Absence alerts

Recommended addition:

Notification preference center
Delivery logs
Failed message retry
Template approval for WhatsApp
Marketing vs transactional message separation

16. Integrations Center

Required integrations:

Zoom
YouTube
Vimeo
Secure video storage
SMS provider
WhatsApp official API
Email SMTP/provider
Payment gateway
Object storage

The reference product lists integrations for Zoom, YouTube, Vimeo and WhatsApp as advanced capabilities.

Recommended addition:

Per-tenant integration settings
Platform-level default integrations
Integration health checks
Webhook logs
API credential vault

17. Device Authentication & Account Sharing Prevention

Required features:

Admin sets allowed device count
Student devices are registered on login
Block login after device limit
Admin can remove/reset devices
Device history
Login activity
Suspicious login detection

Recommended device policy:

Plan-level default device limit
Tenant-level override
Course-level override if needed
Student-level exception
Cooldown period after device reset

Example:

Default: 2 devices per student
Premium course: 1 device only
Admin can reset device after verification

The reference product includes limiting student logins with the same credentials as an advanced feature.

18. Smart Expiry / Access Control

Required features:

Course expiry
Session expiry
Material expiry
Video expiry
Payment-based expiry
Reactivation request
Reactivation payment
Admin approval

Recommended addition:

Expiry rules engine
Grace period
Auto reminder before expiry
Bulk expiry extension
Student-specific override

19. Reviews & Testimonials

Required features:

Student course review
Star rating
Written feedback
Moderation workflow
Approve/reject review
Course-level review toggle
Public display control

Reference product includes student reviews for courses as an advanced feature.

Recommended addition:

Verified enrollment-only reviews
Review abuse reporting
Featured testimonials
Teacher response to review

20. Reports & Analytics

Required features:

Student reports
Teacher reports
Course reports
Payment reports
Attendance reports
Exam reports
Finance reports
Tenant reports
Platform reports

Recommended SaaS analytics:

Tenant active students
Tenant revenue
Tenant churn risk
Most watched courses
Video bandwidth usage
Storage usage
Payment pending count
Device violations
Support ticket volume

The reference platform lists analytics as a key platform area.

4. Extra features I recommend adding

From solution architecture experience, I would add these even if not shown in the reference product.

A. Audit Log Module

Very important for admin, finance, security, and support.

Track:

Who changed course price
Who approved payment
Who reset student device
Who extended access
Who deleted material
Who changed settlement amount
Who impersonated a user

B. Support / Helpdesk Module

For a SaaS LMS, support becomes a major operational need.

Features:

Student support tickets
Teacher support tickets
Tenant support tickets
Ticket assignment
Ticket status
Internal notes
Related payment/course/student links

C. Public Website / Course Storefront

Each tenant should have a public-facing storefront.

Features:

Tenant homepage
Teacher profile
Course listing
Course detail page
Reviews
FAQ
Payment call-to-action
SEO fields
Custom domain support

D. Feature Flag & Plan Limit Engine

Needed for SaaS monetization.

Examples:

Max active students
Max teachers
Max staff
Video storage quota
Bandwidth quota
Device limit
White-label enabled/disabled
Custom domain enabled/disabled
Advanced exams enabled/disabled
SMS/WhatsApp enabled/disabled

The reference pricing model uses plan-based limits such as active students, teachers, operators, advanced features, and plugins, so your platform should be designed with a proper feature/limit engine from day one.

E. Notification Automation Engine

Examples:

Payment pending reminder
Class starts in 30 minutes
Student absent alert
Exam result published
Course access expiring
Device limit exceeded
Payment slip rejected
New material uploaded

F. AI Assistant Later Phase

Not MVP, but future-ready:

Generate quizzes from notes
Summarize lesson materials
Suggest weak students
Generate teacher reports
Generate course descriptions
Detect suspicious payment slips

5. Suggested MVP vs Phase 2 vs Phase 3

MVP / Phase 1

Build these first:

Multi-tenant foundation
Student login
Teacher login
Admin login
Tenant management
Student management
Teacher management
Staff roles
Course management
Lesson/material management
Centralized payments
Manual payment slip upload/approval
Enrollment/access control
Basic video/session access
Basic attendance
Basic exams
Email notifications
Docker deployment
Audit logs

Phase 2

Finance dashboard
Tutor settlement module
Advanced reports
Device authentication
View limits
Session smart expiry
Zoom integration
SMS integration
WhatsApp integration
Course reviews
White-label branding
Custom domain

Phase 3

Tenant-specific payment accounts
Split payments
Secure video storage integration
YouTube/Vimeo management
Dynamic PDF watermarking
Smart payment slip OCR
Mobile apps
Smart cards
Advanced analytics
AI-assisted tools

6. Final module architecture direction

For the final architecture document, I will organize the system into these backend domains:

identity-access-service
tenant-management
user-management
course-management
content-management
video-access-management
live-class-management
enrollment-management
payment-management
ledger-settlement-management
attendance-management
exam-management
finance-expense-management
notification-management
integration-management
reporting-analytics
audit-log-management
support-management

Even if we implement this as a modular monolith in Spring Boot, these module boundaries should be clearly defined from the beginning.

7. Key architectural decisions now confirmed
Architecture style:
Modular monolith first, microservices later only if needed.

Deployment:
Docker-based platform deployment.

Tenancy:
Shared SaaS application with tenant-aware database model.

Payment:
Platform centrally collects payments first.

Settlement:
Tenant/tutor settlement handled internally.

Future payment flexibility:
Tenant-specific payment accounts and split payments later.

Login portals:
Student, Teacher, Admin.

Branding:
White-label and custom domain support.

Security:
Device authentication, view limits, expiry rules, audit logs.

Video:
External secure video/storage provider, not self-hosted video on VPS.

Communication:
Email, SMS, WhatsApp, in-app notifications.
8. Next deliverable I can prepare

The next logical output is the full document:

Comprehensive SaaS LMS Solution Architecture Document

It should include:

1. Executive summary
2. Business goals
3. User roles
4. Functional requirements
5. Non-functional requirements
6. System context diagram
7. High-level architecture
8. Frontend architecture
9. Backend architecture
10. Database architecture
11. Multi-tenancy architecture
12. Payment architecture
13. Enrollment/access architecture
14. Video security architecture
15. Communication architecture
16. Integration architecture
17. DevOps/deployment architecture
18. Security architecture
19. Testing strategy
20. Implementation roadmap
21. Claude Code / vibe-coding instructions
22. Suggested folder structure
23. Initial database schema
24. API module design