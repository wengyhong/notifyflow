<script lang="ts">
  import AuthShell from '#lib/components/AuthShell.svelte';
	import { getCsrfToken } from '#lib/api/csrf.ts';

	let message = $state('');
	let submitting = $state(false);

	async function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		if (submitting) return;

		message = '';
		const form = event.currentTarget;
		if (!(form instanceof HTMLFormElement)) return;

		const formData = new FormData(form);
		const email = String(formData.get('email') ?? '');
		const password = String(formData.get('password') ?? '');
		const tenantName = String(formData.get('tenantName') ?? '');

		if (Array.from(password).length < 15 || new TextEncoder().encode(password).length > 72) {
			message = 'Password must contain at least 15 characters and use at most 72 UTF-8 bytes.';
			return;
		}
		if (!email.trim() || !tenantName.trim() || !password.trim()) {
			message = 'Please complete all fields.';
			return;
		}

		submitting = true;
		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				message = 'Unable to prepare registration. Please try again.';
				return;
			}
			const response = await fetch('/api/auth/register', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					[csrf.headerName]: csrf.token
				},
				body: JSON.stringify({ email, password, tenantName })
			});

			if (response.status === 201) {
				form.reset();
				message = 'Account and workspace created. You can now log in.';
			} else if (response.status === 409) {
				message = 'This email is already registered. Log in or use another email.';
			} else if (response.status === 400) {
				message = 'Check your email, workspace name, and password requirements.';
			} else {
				message = 'Unable to register. Please try again.';
			}
		} catch {
			message = 'Unable to connect. Please try again.';
		} finally {
			submitting = false;
		}
	}
</script>

<svelte:head><title>Create account | NotifyFlow</title></svelte:head>
<AuthShell eyebrow="LET'S GET STARTED" title="Make room for your events." description="Create your account and first workspace.">
  <form onsubmit={handleSubmit}>
    <div><label for="email">Email address</label><input id="email" name="email" type="email" autocomplete="username" maxlength="254" placeholder="you@company.com" required /></div>
    <div><label for="tenantName">Workspace name</label><input id="tenantName" name="tenantName" type="text" maxlength="100" placeholder="Your team or project" required /></div>
    <div><label for="password">Password</label><input id="password" name="password" type="password" autocomplete="new-password" aria-describedby="password-help" placeholder="Create a strong password" required /><p id="password-help">At least 15 characters and at most 72 UTF-8 bytes.</p></div>
    <button type="submit" disabled={submitting}>{submitting ? 'Creating your workspace...' : 'Create your workspace'}</button>
  </form>
  <p role="status">{message}</p>
  <p class="auth-switch">Already have an account? <a href="/login">Sign in</a></p>
</AuthShell>
