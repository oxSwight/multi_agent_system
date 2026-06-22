const AUTO_FILL_BUTTON_ID = 'job-app-autofill-btn';

function findApplicationForm() {
  const forms = document.querySelectorAll('form');
  for (const form of forms) {
    const inputs = form.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"]');
    if (inputs.length >= 2) {
      return form;
    }
  }
  return null;
}

function injectAutoFillButton() {
  if (document.getElementById(AUTO_FILL_BUTTON_ID)) {
    return;
  }

  const form = findApplicationForm();
  if (!form) {
    return;
  }

  const button = document.createElement('button');
  button.id = AUTO_FILL_BUTTON_ID;
  button.type = 'button';
  button.textContent = 'Auto-Fill Profile';
  button.style.cssText = [
    'margin: 8px 0',
    'padding: 8px 12px',
    'border: none',
    'border-radius: 6px',
    'background: #4f46e5',
    'color: #fff',
    'font-size: 13px',
    'cursor: pointer',
  ].join(';');

  button.addEventListener('click', async () => {
    const result = await chrome.storage.local.get('userProfile');
    const profile = result.userProfile;
    if (!profile) {
      button.textContent = 'No saved profile';
      return;
    }

    const nameInput = form.querySelector('input[name*="name" i], input[id*="name" i]');
    const emailInput = form.querySelector('input[type="email"], input[name*="email" i]');
    const phoneInput = form.querySelector('input[type="tel"], input[name*="phone" i]');

    if (nameInput && profile.name) {
      nameInput.value = profile.name;
      nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (emailInput && profile.email) {
      emailInput.value = profile.email;
      emailInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (phoneInput && profile.phone) {
      phoneInput.value = profile.phone;
      phoneInput.dispatchEvent(new Event('input', { bubbles: true }));
    }

    button.textContent = 'Profile applied';
  });

  form.prepend(button);
}

injectAutoFillButton();
