<script lang="ts">
  import AuthShell from '#lib/components/AuthShell.svelte';

import { goto } from '$app/navigation';
import { getCsrfToken } from '#lib/api/csrf.ts';

  let message = $state("");
  let submitting = $state(false);
  async function handleSubmit(event: SubmitEvent) {
    event.preventDefault();

    if(submitting)
    {
        return;
    }
    message = "";
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement)) {
      return;
    }

    const formData = new FormData(form);

    const body = new URLSearchParams({
      email: String(formData.get("email") ?? ""),
      password: String(formData.get("password") ?? ""),
    });

    submitting = true;
    try {
      const csrf = await getCsrfToken();

      if (!csrf) {
        message = "Unable to prepare login. Please try again.";

        return;
      }

      const loginResponse = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          [csrf.headerName]: csrf.token,
        },
        body,
      });

      if (loginResponse.ok) {
        form.reset();
        message = "Logged in successfully";
        await goto('/dashboard');

      } else {
        message = "Login failed";
      }
    } catch {
      message = "Unable to connect. Please try again.";
    }
    finally
    {
        submitting = false;
    }
  }
</script>

<svelte:head><title>Log in | NotifyFlow</title></svelte:head>
<AuthShell eyebrow="WELCOME BACK" title="Good to see you again." description="Sign in to your NotifyFlow workspace.">
  <form onsubmit={handleSubmit}>
    <div><label for="email">Email address</label><input id="email" name="email" type="email" autocomplete="username" placeholder="you@company.com" required /></div>
    <div><label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" placeholder="Enter your password" required /></div>
    <button type="submit" disabled={submitting}>{submitting ? 'Signing in...' : 'Sign in to your workspace'}</button>
  </form>
  <p role="status">{message}</p>
  <p class="auth-switch">New to NotifyFlow? <a href="/register">Create an account</a></p>
</AuthShell>
