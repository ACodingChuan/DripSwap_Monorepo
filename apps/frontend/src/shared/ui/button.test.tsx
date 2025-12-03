import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Button } from './button';

describe('Button', () => {
  it('renders the provided label and defaults to primary variant', () => {
    render(<Button>Primary action</Button>);
    const button = screen.getByRole('button', { name: 'Primary action' });

    expect(button).toBeInTheDocument();
    expect(button.className).toMatch(/bg-primary/);
  });

  it('calls onClick when enabled and ignores events when disabled', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();

    const { rerender } = render(<Button onClick={onClick}>Trigger</Button>);
    const activeButton = screen.getByRole('button', { name: 'Trigger' });

    await user.click(activeButton);
    expect(onClick).toHaveBeenCalledTimes(1);

    rerender(
      <Button onClick={onClick} disabled>
        Trigger
      </Button>
    );

    const disabledButton = screen.getByRole('button', { name: 'Trigger' });
    await user.click(disabledButton);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
