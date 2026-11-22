<script lang="ts">
	import { goto } from '$app/navigation';
	import { getCsrfToken } from '#lib/api/csrf.ts';

	let submitting = $state(false);
	let message = $state('');

	async function logout() {
		if (submitting) return;

		submitting = true;
		message = '';

		try {
			const csrf = await getCsrfToken();

			if (!csrf) {
				message = 'Unable to prepare logout. Please try again.';
				return;
			}

			const response = await fetch('/api/auth/logout', {
				method: 'POST',
				headers: {
					[csrf.headerName]: csrf.token
				}
			});

			if (!response.ok) {
				message = 'Unable to log out. Please try again.';
				return;
			}

			await goto('/login');
		} catch {
			message = 'Unable to connect. Please try again.';
		} finally {
			submitting = false;
		}
	}
</script>

<button type="button" onclick={logout} disabled={submitting}>
	{submitting ? 'Logging out...' : 'Log out'}
</button>

<p role="status">{message}</p>
